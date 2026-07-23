package com.devknow.rag.router;

import com.devknow.auth.RoleLevelMapper;
import com.devknow.auth.UserKnowledgeRole;
import com.devknow.config.rerank.LevelResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 动态路由 —— 针对单次 query 预测最优 RAG 参数。
 *
 * <p>与静态 YAML 策略的区别：
 * <ul>
 *   <li>静态：{@code RagStrategyRouter} 启动时加载，所有 query 共用一套参数</li>
 *   <li>动态：每次查询由 {@code fastChatLanguageModel} 分析 query 特征，
 *       输出量身定制的参数组合</li>
 * </ul>
 *
 * <p>路由特征：
 * <ul>
 *   <li>query 长度 — 短查询 vs 长查询</li>
 *   <li>代码关键词 — 是否涉及方法/类/调用链</li>
 *   <li>架构关键词 — 是否涉及设计/架构/规范</li>
 *   <li>L1~L5 层级 — 由 LevelClassifier 提供</li>
 *   <li>用户角色 — 由 RoleLevelMapper 提供</li>
 * </ul>
 *
 * <p>使用 fastChatLanguageModel（轻量模型），每个 query 仅 1 次 LLM 调用。
 * 调用失败时降级为 YAML 默认参数，不影响主流程。
 */
@Slf4j
@Component
public class DynamicRouter {

    private final ChatLanguageModel fastModel;
    private final ObjectMapper objectMapper;

    @Value("${app.rag.dynamic-router.enabled:true}")
    private boolean enabled;

    public DynamicRouter(@Qualifier("fastChatLanguageModel") ChatLanguageModel fastModel,
                         ObjectMapper objectMapper) {
        this.fastModel = fastModel;
        this.objectMapper = objectMapper;
    }

    // ==================== 公共入口 ====================

    /**
     * 对 query 进行动态路由，返回预测的参数。
     *
     * @param question 用户问题
     * @param levelResult 层级分类结果（可能为 null）
     * @param role 用户角色（可能为 null）
     * @return 动态路由结果，never null
     */
    public DynamicRouteResult route(String question,
                                     LevelResult levelResult,
                                     UserKnowledgeRole role) {
        if (!enabled || question == null || question.isBlank()) {
            return new DynamicRouteResult();
        }

        long start = System.nanoTime();

        try {
            // 1. 提取查询特征
            QueryFeatures features = extractFeatures(question);

            // 2. LLM 预测最优参数
            DynamicRouteResult result = predict(features, levelResult, role);

            long elapsed = (System.nanoTime() - start) / 1_000_000;
            log.info("DynamicRouter: q={}, features=[len={}, code={}, arch={}, detail={}], result=[cs={}, hyde={}, mmr={}, topK={}, rt={}], reasoning={}, 耗时={}ms",
                    truncate(question, 40),
                    features.length, features.hasCodeKeywords, features.hasArchKeywords, features.hasDetailKeywords,
                    result.getChunkSize(), result.isHydeEnabled(), result.isMmrEnabled(),
                    result.getVectorTopK(), result.getRerankTopN(),
                    result.getReasoning() != null ? truncate(result.getReasoning(), 60) : "",
                    elapsed);

            return result;

        } catch (Exception e) {
            log.warn("DynamicRouter 预测失败，降级为默认参数: {}", e.getMessage());
            return new DynamicRouteResult();
        }
    }

    // ==================== 特征提取 ====================

    /** 代码关键词正则 */
    private static final Pattern CODE_KEYWORD_PATTERN = Pattern.compile(
            "(method|function|class|interface|implement|call|invoke|调用|方法|函数|类|接口|实现|代码|实现逻辑|" +
            "call chain|调用链|method signature|方法签名|参数|return|exception|算法|algorithm|bug|defect)", Pattern.CASE_INSENSITIVE);

    /** 架构/设计关键词正则 */
    private static final Pattern ARCH_KEYWORD_PATTERN = Pattern.compile(
            "(architecture|design|pattern|structure|架构|设计|模式|结构|模块|module|component|组件|" +
            "system|系统|技术选型|decision|decide|规范|standard|约定|convention)", Pattern.CASE_INSENSITIVE);

    /** 细节/具体实现关键词正则 */
    private static final Pattern DETAIL_KEYWORD_PATTERN = Pattern.compile(
            "(how|how to|怎么写|实现|implement|code|代码|line|行|具体|detail|细节|example|示例)", Pattern.CASE_INSENSITIVE);

    private QueryFeatures extractFeatures(String question) {
        QueryFeatures f = new QueryFeatures();
        f.rawQuestion = question;
        f.length = question.length();
        f.hasCodeKeywords = CODE_KEYWORD_PATTERN.matcher(question).find();
        f.hasArchKeywords = ARCH_KEYWORD_PATTERN.matcher(question).find();
        f.hasDetailKeywords = DETAIL_KEYWORD_PATTERN.matcher(question).find();

        // 计算关键词密度
        String[] words = question.split("[\\s,，。；;、．.！!？?（）()【】\\[\\]：:\"'']+");
        int keywordCount = 0;
        for (String w : words) {
            if (w.length() > 1) keywordCount++;
        }
        f.keywordDensity = words.length > 0 ? (double) keywordCount / words.length : 0;

        return f;
    }

    private static class QueryFeatures {
        String rawQuestion;
        int length;
        boolean hasCodeKeywords;
        boolean hasArchKeywords;
        boolean hasDetailKeywords;
        double keywordDensity;
    }

    // ==================== LLM 预测 ====================

    /**
     * 调用 fastChatLanguageModel 预测最优参数。
     * 结构化输出 JSON，解析失败时返回默认值。
     */
    @SuppressWarnings("unchecked")
    private DynamicRouteResult predict(QueryFeatures features,
                                        LevelResult levelResult,
                                        UserKnowledgeRole role) {
        // 构造 prompt
        String prompt = buildPrompt(features, levelResult, role);
        String levelStr = levelResult != null ? String.valueOf(levelResult.getLevel()) : "unknown";
        String roleStr = role != null ? role.name() : "UNSPECIFIED";

        try {
            String response = fastModel.chat(
                    ChatRequest.builder()
                            .messages(List.of(UserMessage.from(prompt)))
                            .build())
                    .aiMessage().text();

            // 解析 JSON
            Map<String, Object> map = parseJsonResponse(response);

            DynamicRouteResult.DynamicRouteResultBuilder builder = DynamicRouteResult.builder();

            if (map.containsKey("chunkSize"))     builder.chunkSize(coerceInt(map.get("chunkSize"), 500));
            if (map.containsKey("hydeEnabled"))    builder.hydeEnabled(coerceBool(map.get("hydeEnabled"), true));
            if (map.containsKey("mmrEnabled"))     builder.mmrEnabled(coerceBool(map.get("mmrEnabled"), true));
            if (map.containsKey("vectorTopK"))     builder.vectorTopK(coerceInt(map.get("vectorTopK"), 8));
            if (map.containsKey("rerankTopN"))     builder.rerankTopN(coerceInt(map.get("rerankTopN"), 4));
            if (map.containsKey("crossEncoderWeight")) builder.crossEncoderWeight(coerceDouble(map.get("crossEncoderWeight"), 0.7));
            if (map.containsKey("reasoning"))      builder.reasoning(String.valueOf(map.get("reasoning")));

            return builder.build();

        } catch (Exception e) {
            log.warn("DynamicRouter LLM 预测失败 (level={}, role={}): {}", levelStr, roleStr, e.getMessage());
            return new DynamicRouteResult();
        }
    }

    /**
     * 构建 LLM Prompt。
     * 使用 few-shot 示例引导 LLM 输出结构化决策。
     */
    private String buildPrompt(QueryFeatures features, LevelResult levelResult, UserKnowledgeRole role) {
        String question = features.rawQuestion != null ? features.rawQuestion : "";
        String level = levelResult != null ? "L" + levelResult.getLevel() : "unknown";
        String levelDesc = levelResult != null ? levelResult.getReason() : "";
        String roleName = role != null ? role.name() : "UNSPECIFIED";

        return """
                你是一个 RAG 检索策略优化器。根据用户问题的特征，选择最优的检索参数。

                特征说明：
                - chunkSize: 文本块大小（越小越精准，越大越完整）
                - hydeEnabled: 是否用 HyDE（假设文档）增强检索（模糊查询用，精确查询不用）
                - mmrEnabled: 是否用 MMR 多样性去重（覆盖面广的查询用，精确查找不用）
                - vectorTopK: 向量检索返回条数（越多越全面，但噪声也越多）
                - rerankTopN: 重排后保留条数（进入 Prompt 的片段数）
                - crossEncoderWeight: Cross-encoder 评分权重（越高越依赖语义重排）

                ## 决策规则

                1. 如果问题涉及"怎么实现/怎么写代码/代码细节" → chunkSize=384, hyde=true, mmr=false, vectorTopK=6, rerankTopN=3
                2. 如果问题涉及"架构/设计/为什么" → chunkSize=500, hyde=true, mmr=true, vectorTopK=10, rerankTopN=5
                3. 如果问题是"这是什么/概述/简介" → chunkSize=800, hyde=false, mmr=true, vectorTopK=6, rerankTopN=3
                4. 如果问题非常简短（<15字）→ chunkSize=500, hyde=false, mmr=true, vectorTopK=6
                5. 如果问题很长且复杂（>80字）→ 扩大搜索：chunkSize=800, hyde=true, mmr=true, vectorTopK=12
                6. 默认 → chunkSize=500, hyde=true, mmr=true, vectorTopK=8, rerankTopN=4

                ## 当前查询特征

                - 查询内容：%s
                - 查询长度：%d 字
                - 含代码关键词：%s
                - 含架构关键词：%s
                - 含细节关键词：%s
                - 知识层级：%s（%s）
                - 用户角色：%s

                请只返回 JSON，格式：
                {"chunkSize": int, "hydeEnabled": bool, "mmrEnabled": bool, "vectorTopK": int, "rerankTopN": int, "crossEncoderWeight": float, "reasoning": "str"}
                """.formatted(
                        truncate(question, 150),
                        features.length,
                        features.hasCodeKeywords, features.hasArchKeywords, features.hasDetailKeywords,
                        level, levelDesc, roleName
                );
    }

    // ==================== JSON 解析 ====================

    private Map<String, Object> parseJsonResponse(String response) {
        String json = response;
        if (json.contains("```json")) {
            json = json.substring(json.indexOf("```json") + 7);
            json = json.substring(0, json.indexOf("```"));
        } else if (json.contains("```")) {
            json = json.substring(json.indexOf("```") + 3);
            json = json.substring(0, json.indexOf("```"));
        }
        json = json.trim();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse router response: " + e.getMessage(), e);
        }
    }

    private int coerceInt(Object val, int defaultValue) {
        if (val instanceof Number) return ((Number) val).intValue();
        try { return Integer.parseInt(String.valueOf(val)); } catch (Exception e) { return defaultValue; }
    }

    private boolean coerceBool(Object val, boolean defaultValue) {
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof String) return "true".equalsIgnoreCase((String) val);
        return defaultValue;
    }

    private double coerceDouble(Object val, double defaultValue) {
        if (val instanceof Number) return ((Number) val).doubleValue();
        try { return Double.parseDouble(String.valueOf(val)); } catch (Exception e) { return defaultValue; }
    }

    private static String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : (s != null ? s : "");
    }
}
