package com.devknow.rag.sparse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 稀疏编码器 —— 使用 LLM 从查询中提取关键术语并赋予权重。
 *
 * <p>模拟 SPLADE 的行为：给定一段文本，输出一个稀疏向量
 *（一组 (term, weight) 对），其中 weight 表示该 term 对查询的重要性。
 *
 * <p>与真实 SPLADE 的差异：
 * <ul>
 *   <li>真实 SPLADE：经过训练的神经网络，一次 forward 即输出全部权重</li>
 *   <li>本实现：使用 fastChatLanguageModel 提取 + 权重，每次 query 约 1 次 LLM 调用</li>
 * </ul>
 *
 * <p>用于增强现有关键词搜索：查询不再是同义词列表的平面展开，
 * 而是带权重的关键术语，搜索结果按权重排序。
 */
@Slf4j
@Component
public class SparseEncoder {

    private final ChatLanguageModel fastModel;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public SparseEncoder(@Qualifier("fastChatLanguageModel") ChatLanguageModel fastModel,
                          ObjectMapper objectMapper,
                          @Value("${app.rag.sparse-encoder.enabled:true}") boolean enabled) {
        this.fastModel = fastModel;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    /**
     * 对查询进行稀疏编码，返回带权重的关键术语列表。
     *
     * @param query 用户查询
     * @return 稀疏向量（按 weight 降序），编码失败时返回空向量
     */
    public SparseVector encode(String query) {
        if (!enabled || query == null || query.isBlank()) {
            return new SparseVector(List.of());
        }

        long start = System.nanoTime();

        try {
            String prompt = """
                    你是查询关键词提取器。从以下查询中提取最重要的搜索关键词，并为每个关键词赋予权重。

                    规则：
                    - 权重范围 0.0 ~ 1.0，表示该词对查询的重要程度
                    - 核心概念/实体/技术术语权重应最高（0.8~1.0）
                    - 修饰性、通用词权重中低（0.1~0.4）
                    - 提取 3~8 个关键词，不要过多
                    - 如果查询中出现方法名、类名、文件名，保留原样并给予高权重

                    查询：%s

                    输出 JSON 数组，格式：
                    [{"term": "createOrder", "weight": 0.95}, {"term": "OrderService", "weight": 0.90}, {"term": "订单", "weight": 0.70}]
                    只返回 JSON，不要 markdown 标记。
                    """.formatted(query);

            String response = fastModel.chat(
                            ChatRequest.builder()
                                    .messages(List.of(UserMessage.from(prompt)))
                                    .build())
                    .aiMessage().text();

            SparseVector result = parseResponse(response);
            long elapsed = (System.nanoTime() - start) / 1_000_000;

            log.debug("SparseEncoder: q={}, terms={}, 耗时={}ms",
                    truncate(query, 40),
                    result.getEntries().stream().map(e -> e.getTerm() + ":" + String.format("%.2f", e.getWeight())).collect(Collectors.joining(", ")),
                    elapsed);

            return result;

        } catch (Exception e) {
            log.warn("SparseEncoder 失败，降级为空向量: {}", e.getMessage());
            return new SparseVector(List.of());
        }
    }

    /**
     * 批量编码（用于离线索引文档）。
     * 注意：在线查询走 encode()，索引只在增量更新时用此方法。
     */
    public List<SparseVector> encodeBatch(List<String> texts) {
        return texts.stream()
                .map(this::encode)
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private SparseVector parseResponse(String response) {
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
            List<Map<String, Object>> raw = objectMapper.readValue(
                    json, new TypeReference<List<Map<String, Object>>>() {});

            List<SparseVector.Entry> entries = raw.stream()
                    .map(m -> {
                        String term = (String) m.get("term");
                        double weight = m.get("weight") instanceof Number
                                ? ((Number) m.get("weight")).doubleValue()
                                : 0.5;
                        return term != null && !term.isBlank()
                                ? new SparseVector.Entry(term.strip(), Math.max(0, Math.min(1, weight)))
                                : null;
                    })
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingDouble(SparseVector.Entry::getWeight).reversed())
                    .collect(Collectors.toList());

            return new SparseVector(entries);

        } catch (Exception e) {
            log.warn("SparseEncoder 响应解析失败: {}", e.getMessage());
            return new SparseVector(List.of());
        }
    }

    private static String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : (s != null ? s : "");
    }
}
