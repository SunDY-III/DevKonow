package com.devknow.rag;

import com.devknow.vector.ScoredChunk;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 幻觉三关鉴定器。
 *
 * <p>在 RAG 检索 → 生成 → 输出的全链路中插入三个鉴定点，逐级缩紧信任：
 *
 * <ol>
 *   <li><b>Chunk 相关性过滤</b> — LLM 逐条判断每个 chunk 是否与用户问题相关，
 *       不相关的 chunk 在进入 Prompt 前被过滤掉，从源头减少幻觉素材。</li>
 *   <li><b>答案事实验证</b> — LLM 将生成的答案与检索到的文档逐句比对，
 *       标记"有文档支持"和"无文档支持"的句子，仅保留有支持的论断。</li>
 *   <li><b>逐字引用追溯</b> — LLM 为答案中每个关键论断找到源文档中的
 *       逐字原文片段并附上引用，确保每句话都可追溯到出处。</li>
 * </ol>
 *
 * <p>CRAG 高置信跳过：当检索置信度 >= 0.85 时，跳过第一关和第二关 LLM 调用，
 * 直接使用检索结果，降低延迟和 Token 消耗。
 */
@Slf4j
@Component
public class HallucinationGuard {

    /** 轻量模型：第一关（chunk 判题）和第二关（事实验证）用 —— 二元分类，小模型足够 */
    private final dev.langchain4j.model.chat.ChatLanguageModel fastModel;

    /** 主力模型：第三关（引用追溯）用 —— 需要精确文本匹配能力 */
    private final dev.langchain4j.model.chat.ChatLanguageModel deepModel;

    /** 高置信阈值：超过此值时跳过第一关和第二关 LLM 调用 */
    private static final double HIGH_CONFIDENCE_THRESHOLD = 0.85;

    private final ObjectMapper jsonMapper = new ObjectMapper();

    public HallucinationGuard(
            @org.springframework.beans.factory.annotation.Qualifier("fastChatLanguageModel")
            dev.langchain4j.model.chat.ChatLanguageModel fastModel,
            @org.springframework.beans.factory.annotation.Qualifier("chatLanguageModel")
            dev.langchain4j.model.chat.ChatLanguageModel deepModel) {
        this.fastModel = fastModel;
        this.deepModel = deepModel;
    }

    // ==================== 第一关：Chunk 相关性过滤 ====================

    /**
     * 第一关：LLM 逐条判定 chunk 是否与用户问题相关。
     * 只有判定为"相关"的 chunk 才会放行到下一关。
     *
     * @param question 用户问题
     * @param chunks   候选 chunk 列表
     * @return 过滤后仅保留相关的 chunk
     */
    public List<ScoredChunk> filterRelevantChunks(String question, List<ScoredChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return chunks;
        if (chunks.size() <= 2) return chunks; // 数量少时跳过，不值得一次 LLM 调用

        long start = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个检索质量评审员。判断以下每个片段与用户问题的相关程度。\n\n");
        sb.append("用户问题：").append(question).append("\n\n");
        sb.append("请逐条判断，只输出 JSON 数组，格式：\n");
        sb.append("[{\"index\": 1, \"relevant\": true/false, \"reason\": \"一句话原因\"}, ...]\n\n");

        for (int i = 0; i < chunks.size(); i++) {
            ScoredChunk c = chunks.get(i);
            String content = c.getContent();
            String fileName = c.getFileName();
            sb.append("--- 片段 ").append(i + 1).append(" ---\n");
            if (fileName != null && !fileName.isBlank()) {
                sb.append("来源: ").append(fileName).append("\n");
            }
            // 截取前 500 字符做判断即可
            String snippet = content != null
                    ? (content.length() > 500 ? content.substring(0, 500) + "..." : content)
                    : "";
            sb.append("内容: ").append(snippet).append("\n\n");
        }

        try {
            String response = fastModel.chat(
                    dev.langchain4j.data.message.UserMessage.from(sb.toString()))
                    .aiMessage().text();

            List<Map<String, Object>> judgments = parseJsonArray(response);
            Set<Integer> relevantIndexes = new HashSet<>();
            for (Map<String, Object> j : judgments) {
                int idx = ((Number) j.get("index")).intValue();
                boolean relevant = Boolean.TRUE.equals(j.get("relevant"));
                if (relevant) {
                    relevantIndexes.add(idx);
                }
            }

            List<ScoredChunk> filtered = new ArrayList<>();
            int filteredOut = 0;
            for (int i = 0; i < chunks.size(); i++) {
                if (relevantIndexes.contains(i + 1)) {
                    filtered.add(chunks.get(i));
                } else {
                    filteredOut++;
                }
            }

            log.info("[幻觉-第一关] 输入={}, 通过={}, 过滤={}, 耗时={}ms",
                    chunks.size(), filtered.size(), filteredOut,
                    System.currentTimeMillis() - start);

            // 如果全部被过滤掉，说明 LLM 判断有误，保留至少 1 条
            return filtered.isEmpty() ? List.of(chunks.get(0)) : filtered;

        } catch (Exception e) {
            log.warn("[幻觉-第一关] LLM 判定失败，放行全部: {}", e.getMessage());
            return chunks;
        }
    }

    // ==================== 第二关：答案事实验证 ====================

    /**
     * 第二关：将生成的答案与检索到的文档逐句比对。
     * 每个句子标记为 SUPPORTED / UNSUPPORTED，仅保留 SUPPORTED 的句子。
     *
     * @param answer    LLM 生成的原始答案
     * @param chunks    源文档 chunk 列表
     * @return 仅包含有支持论断的清洗后答案
     */
    public FactCheckResult verifyAnswer(String answer, List<ScoredChunk> chunks) {
        if (answer == null || answer.isBlank()) {
            return new FactCheckResult("", List.of(), 0, 0);
        }

        long start = System.currentTimeMillis();
        String sourceText = chunks.stream()
                .map(c -> {
                    String f = c.getFileName() != null ? c.getFileName() : "unknown";
                    return "[来源 " + f + "]\n" + (c.getContent() != null ? c.getContent() : "");
                })
                .collect(Collectors.joining("\n\n"));

        // 截断源文本避免超出 LLM 上下文
        if (sourceText.length() > 8000) {
            sourceText = sourceText.substring(0, 8000) + "\n...（截断）";
        }

        String prompt = String.format("""
                你是一个事实一致性检查员。判断以下答案中的每个论断是否被提供的源文档所支持。

                源文档：
                %s

                待验证的答案：
                %s

                请按以下 JSON 格式输出：
                {
                  "claims": [
                    {"sentence": "答案中的具体句子", "supported": true/false, "evidence": "源文档中的支持原文或 null"},
                    ...
                  ],
                  "overall_verdict": "PASS" 或 "PARTIAL" 或 "FAIL"
                }

                规则：
                - supported=true 表示该句子在源文档中有明确依据
                - supported=false 表示该句子在源文档中找不到依据
                - evidence 字段写出支持该句子的原文片段，没有则写 null
                - overall_verdict: PASS=全部有支持, PARTIAL=部分有支持, FAIL=全无支持
                只返回 JSON，不要其他文字。
                """,
                sourceText, answer);

        try {
            String response = fastModel.chat(
                    dev.langchain4j.data.message.UserMessage.from(prompt))
                    .aiMessage().text();

            Map<String, Object> result = parseJsonObject(response);
            List<Map<String, Object>> claims = (List<Map<String, Object>>) result.get("claims");

            int totalClaims = claims.size();
            int supportedCount = 0;
            StringBuilder cleanAnswer = new StringBuilder();

            for (Map<String, Object> claim : claims) {
                boolean supported = Boolean.TRUE.equals(claim.get("supported"));
                String sentence = (String) claim.get("sentence");
                if (supported && sentence != null) {
                    cleanAnswer.append(sentence).append("\n");
                    supportedCount++;
                }
            }

            String verdict = (String) result.get("overall_verdict");
            log.info("[幻觉-第二关] 论断={}, 支持={}, 无关={}, verdict={}, 耗时={}ms",
                    totalClaims, supportedCount, totalClaims - supportedCount, verdict,
                    System.currentTimeMillis() - start);

            String finalAnswer = cleanAnswer.toString().trim();
            if (finalAnswer.isEmpty()) {
                finalAnswer = answer; // 全部被过滤时保留原始答案，避免返回空
            }

            return new FactCheckResult(finalAnswer, claims, totalClaims, supportedCount);

        } catch (Exception e) {
            log.warn("[幻觉-第二关] 验证失败，放行原始答案: {}", e.getMessage());
            return new FactCheckResult(answer, List.of(), 0, 0);
        }
    }

    // ==================== 第三关：逐字引用追溯 ====================

    /**
     * 第三关：将第二关验证通过的答案中的每个关键论断，追溯到源文档的逐字原文。
     * 输出带精确引用的答案，引用格式为 [来源:文件名, 片段编号]。
     *
     * @param verifiedAnswer 第二关验证后的答案
     * @param chunks         源文档 chunk 列表
     * @return 带逐字引用的最终答案
     */
    public String traceCitations(String verifiedAnswer, List<ScoredChunk> chunks) {
        if (verifiedAnswer == null || verifiedAnswer.isBlank()) {
            return verifiedAnswer;
        }

        long start = System.currentTimeMillis();

        // 构建带编号的源文档列表供 LLM 引用
        StringBuilder sourceBuilder = new StringBuilder();
        Map<Integer, ScoredChunk> indexMap = new HashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            ScoredChunk c = chunks.get(i);
            String fileName = c.getFileName() != null ? c.getFileName() : "unknown";
            String content = c.getContent() != null ? c.getContent() : "";
            sourceBuilder.append("--- [" + (i + 1) + "] ").append(fileName).append(" ---\n")
                    .append(content).append("\n\n");
            indexMap.put(i + 1, c);
        }

        String sources = sourceBuilder.toString();
        if (sources.length() > 10000) {
            sources = sources.substring(0, 10000) + "\n...（截断）";
        }

        String prompt = String.format("""
                你是一个引用精确性检查员。你的任务是为以下答案中的每个关键论断，
                从源文档中找到支持它的逐字原文片段（不是释义，不是摘要），并标注来源编号。

                源文档（带编号）：
                %s

                答案：
                %s

                请输出 JSON 格式：
                {
                  "cited_answer": "在原始答案的每个关键论断后添加引用标记 [来源:N]，并整合为完整回答文本",
                  "citations": [
                    {
                      "source_index": 1,
                      "excerpt": "源文档中的逐字原文片段（必须与源文档完全一致）",
                      "matched_claim": "答案中对应的论断"
                    },
                    ...
                  ]
                }

                规则：
                - excerpt 必须是源文档中的逐字原文，不能改写或概括
                - 如果某个论断在源文档中找不到逐字原文，不要编造引用
                - cited_answer 是可直接展示给用户的最终答案文本
                只返回 JSON，不要其他文字。
                """,
                sources, verifiedAnswer);

        try {
            String response = deepModel.chat(
                    dev.langchain4j.data.message.UserMessage.from(prompt))
                    .aiMessage().text();

            Map<String, Object> result = parseJsonObject(response);
            String citedAnswer = (String) result.get("cited_answer");

            List<Map<String, Object>> citations = (List<Map<String, Object>>) result.get("citations");
            int citationCount = citations != null ? citations.size() : 0;

            log.info("[幻觉-第三关] 引用数={}, 耗时={}ms",
                    citationCount, System.currentTimeMillis() - start);

            return citedAnswer != null && !citedAnswer.isBlank() ? citedAnswer : verifiedAnswer;

        } catch (Exception e) {
            log.warn("[幻觉-第三关] 追溯失败，放行第二关结果: {}", e.getMessage());
            return verifiedAnswer;
        }
    }

    // ==================== 统一入口（含 CRAG 置信跳过 + 策略检查点掩码） ====================

    /**
     * 全链路执行三个鉴定点。
     * 第一关在检索端调用，第二、三关在生成端调用。
     *
     * <p>CRAG 优化：当置信度 >= HIGH_CONFIDENCE_THRESHOLD (0.85) 时，
     * 跳过第一关（chunk 过滤），加速高置信场景。
     *
     * @param question   用户问题
     * @param chunks     检索结果 chunk 列表
     * @param confidence 检索置信度（来自 CRAG 评估器）
     * @return 经过第一关过滤后的 chunk 列表
     */
    public List<ScoredChunk> executeCheckpoint1(String question, List<ScoredChunk> chunks) {
        return executeCheckpoint1(question, chunks, 0.0, null);
    }

    /**
     * 含置信度参数的第一关。
     *
     * @param confidence 检索置信度，>= HIGH_CONFIDENCE_THRESHOLD 时跳过 LLM 过滤
     */
    public List<ScoredChunk> executeCheckpoint1(String question, List<ScoredChunk> chunks, double confidence) {
        return executeCheckpoint1(question, chunks, confidence, null);
    }

    /**
     * 含置信度 + 策略检查点掩码的第一关。
     *
     * <p>当 skipCheckpoints 包含 1 时，跳过第一关（chunk 相关性过滤），
     * 由策略配置决定（如学习研读场景不使用此关）。
     * CRAG 高置信跳过仍然优先于策略跳过。
     *
     * @param skipCheckpoints 策略指定的跳过列表，包含 1 时跳过第一关
     */
    public List<ScoredChunk> executeCheckpoint1(String question, List<ScoredChunk> chunks,
                                                 double confidence, java.util.List<Integer> skipCheckpoints) {
        // 1) 策略跳过：场景配置要求跳过第一关（防御: 非Integer元素会被contains忽略）
        if (skipCheckpoints != null && skipCheckpoints.contains(Integer.valueOf(1))) {
            log.info("[幻觉-第一关] 策略跳过: skipCheckpoints={}", skipCheckpoints);
            return chunks;
        }
        // 2) CRAG 高置信跳过
        if (confidence >= HIGH_CONFIDENCE_THRESHOLD) {
            log.info("[幻觉-第一关] CRAG 高置信跳过: confidence={:.4f} >= {}", confidence, HIGH_CONFIDENCE_THRESHOLD);
            return chunks;
        }
        // 3) LLM 过滤
        return filterRelevantChunks(question, chunks);
    }

    /**
     * 执行第二、三关（在 LLM 生成答案后调用）。
     *
     * <p>CRAG 优化：当置信度 >= HIGH_CONFIDENCE_THRESHOLD 时，
     * 跳过第二关（事实验证），仅执行第三关引用追溯。
     *
     * @param rawAnswer LLM 生成的原始答案
     * @param chunks    源文档 chunk 列表
     * @return 经过验证和引用追溯的最终答案
     */
    public String executeCheckpoint2And3(String rawAnswer, List<ScoredChunk> chunks) {
        return executeCheckpoint2And3(rawAnswer, chunks, 0.0, null);
    }

    /**
     * 含置信度参数的第二、三关。
     *
     * @param confidence 检索置信度，>= HIGH_CONFIDENCE_THRESHOLD 时跳过第二关
     */
    public String executeCheckpoint2And3(String rawAnswer, List<ScoredChunk> chunks, double confidence) {
        return executeCheckpoint2And3(rawAnswer, chunks, confidence, null);
    }

    /**
     * 含置信度 + 策略检查点掩码的第二、三关。
     *
     * <p>当 skipCheckpoints 包含 2 时跳过第二关（事实验证），
     * 包含 3 时跳过第三关（引用追溯）。
     * 策略跳过优先于 CRAG 高置信跳过。
     *
     * @param skipCheckpoints 策略指定的跳过列表
     */
    public String executeCheckpoint2And3(String rawAnswer, List<ScoredChunk> chunks,
                                          double confidence, java.util.List<Integer> skipCheckpoints) {
        boolean skipC2 = skipCheckpoints != null && skipCheckpoints.contains(2);
        boolean skipC3 = skipCheckpoints != null && skipCheckpoints.contains(3);

        // 第二关
        String factChecked = rawAnswer;
        if (skipC2) {
            log.info("[幻觉-第二关] 策略跳过: skipCheckpoints={}", skipCheckpoints);
        } else if (confidence >= HIGH_CONFIDENCE_THRESHOLD) {
            log.info("[幻觉-第二关] CRAG 高置信跳过: confidence={:.4f} >= {}", confidence, HIGH_CONFIDENCE_THRESHOLD);
        } else {
            FactCheckResult result = verifyAnswer(rawAnswer, chunks);
            factChecked = result.cleanedAnswer();
            if (factChecked == null || factChecked.isBlank()) {
                factChecked = rawAnswer;
            }
        }

        // 第三关
        if (skipC3) {
            log.info("[幻觉-第三关] 策略跳过: skipCheckpoints={}", skipCheckpoints);
            return factChecked;
        }
        return traceCitations(factChecked, chunks);
    }

    // ==================== 辅助方法 ====================

    private List<Map<String, Object>> parseJsonArray(String response) {
        try {
            String json = extractJson(response);
            return jsonMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("JSON 解析失败: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private Map<String, Object> parseJsonObject(String response) {
        try {
            String json = extractJson(response);
            return jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("JSON 解析失败: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private String extractJson(String response) {
        if (response == null) return "{}";
        String json = response;
        if (json.contains("```json")) {
            json = json.substring(json.indexOf("```json") + 7);
            json = json.substring(0, json.indexOf("```"));
        } else if (json.contains("```")) {
            json = json.substring(json.indexOf("```") + 3);
            json = json.substring(0, json.indexOf("```"));
        }
        return json.trim();
    }

    // ==================== 结果类型 ====================

    public record FactCheckResult(
            String cleanedAnswer,
            List<Map<String, Object>> claims,
            int totalClaims,
            int supportedClaims
    ) {
        public double supportRate() {
            return totalClaims > 0 ? (double) supportedClaims / totalClaims : 0;
        }
    }
}
