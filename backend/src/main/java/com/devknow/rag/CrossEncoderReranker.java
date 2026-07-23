package com.devknow.rag;

import com.devknow.vector.ScoredChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Cross-encoder 重排序器。
 *
 * <p>将 query 与每个候选 chunk 拼接送入评分模型，得到精确的相关性分数。
 * 相比 bi-encoder（向量余弦）和启发式加权，cross-encoder 能捕捉 query 与 chunk 间的
 * 深层语义交互，排序精度更高。
 *
 * <p>当前使用 LLM batch scoring 方案：将 query 和 chunks 分组构造评分 prompt，
 * 由 LLM 给出 1-5 的相关性评分。当 LLM 不可用时降级为特征加权。
 *
 * <p>未来可替换为：
 * <ul>
 *   <li>BGE-Reranker-v2 ONNX 本地推理</li>
 *   <li>Cohere Rerank API</li>
 *   <li>Voyage AI Rerank</li>
 * </ul>
 */
@Slf4j
@Component
public class CrossEncoderReranker {

    /** 每批最多评分多少对 (query, chunk) */
    private static final int BATCH_SIZE = 10;

    private final dev.langchain4j.model.chat.ChatLanguageModel fastModel;

    public CrossEncoderReranker(
            @Value("${app.rag.cross-encoder.model:}") String modelName,
            @Qualifier("fastChatLanguageModel") dev.langchain4j.model.chat.ChatLanguageModel fastModel) {
        this.fastModel = fastModel;
    }

    /**
     * Cross-encoder 重排序入口（默认融合权重 0.7）。
     */
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topN) {
        return rerank(query, candidates, topN, 0.7);
    }

    /**
     * Cross-encoder 重排序入口（支持动态融合权重）。
     *
     * @param query        用户查询
     * @param candidates   候选列表（来自 MMR 或特征重排）
     * @param topN         截断数
     * @param fusionWeight Cross-encoder 分融合权重；最终分 = crossScore × fusionWeight + originalScore × (1-fusionWeight)
     * @return 重排序后的 TopN
     */
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topN, double fusionWeight) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        if (candidates.size() <= 1) return candidates;

        long start = System.currentTimeMillis();

        try {
            // 批量 LLM 评分
            List<ScoredChunk> scored = batchScore(query, candidates, fusionWeight);
            List<ScoredChunk> result = scored.stream()
                    .sorted(Comparator.comparingDouble(ScoredChunk::getScore).reversed())
                    .limit(topN)
                    .toList();

            log.info("cross-encoder rerank: candidates={}, result={}, topScore={:.4f}, fusionWeight={}, 耗时={}ms",
                    candidates.size(), result.size(),
                    result.isEmpty() ? 0 : result.get(0).getScore(),
                    fusionWeight,
                    System.currentTimeMillis() - start);
            return result;

        } catch (Exception e) {
            log.warn("Cross-encoder 评分失败，降级为原始排序: {}", e.getMessage());
            return candidates.stream()
                    .sorted(Comparator.comparingDouble(ScoredChunk::getScore).reversed())
                    .limit(topN)
                    .toList();
        }
    }

    /**
     * 批量评分（默认融合权重 0.7）。
     */
    private List<ScoredChunk> batchScore(String query, List<ScoredChunk> candidates) {
        return batchScore(query, candidates, 0.7);
    }

    /**
     * 批量评分：将 candidates 分批次，每批构造一个 prompt 让 LLM 打分。
     * 使用指定的融合权重合成最终分。
     */
    private List<ScoredChunk> batchScore(String query, List<ScoredChunk> candidates, double fusionWeight) {
        List<ScoredChunk> result = new ArrayList<>(candidates.size());

        for (int batchStart = 0; batchStart < candidates.size(); batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE, candidates.size());
            List<ScoredChunk> batch = candidates.subList(batchStart, batchEnd);

            try {
                List<Double> scores = scoreBatch(query, batch);
                for (int i = 0; i < batch.size(); i++) {
                    double originalScore = batch.get(i).getScore();
                    double crossScore = scores.get(i);
                    // 融合：cross-encoder 分 × fusionWeight + 原始分 × (1-fusionWeight)
                    double fused = crossScore * fusionWeight + originalScore * (1.0 - fusionWeight);
                    result.add(new ScoredChunk(
                            batch.get(i).getChunkId(),
                            batch.get(i).getDocId(),
                            batch.get(i).getSeq(),
                            batch.get(i).getFileName(),
                            batch.get(i).getContent(),
                            Math.round(fused * 10000.0) / 10000.0,
                            batch.get(i).getSource(),
                            null));
                }
            } catch (Exception e) {
                log.warn("batch scoring 失败 ({}~{}), 保留原始分", batchStart, batchEnd);
                for (ScoredChunk c : batch) {
                    result.add(c);
                }
            }
        }
        return result;
    }

    /**
     * 单批评分：构造 prompt 让 LLM 给每个 chunk 与 query 的相关性评分（1-5 分）。
     */
    @SuppressWarnings("unchecked")
    private List<Double> scoreBatch(String query, List<ScoredChunk> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个文档相关性评分器。判断以下每个片段与用户问题的相关程度。\n\n");
        sb.append("用户问题：").append(query).append("\n\n");
        sb.append("评分标准：\n");
        sb.append("5 - 完全相关（直接回答了问题的核心）\n");
        sb.append("4 - 高度相关（包含问题的关键信息）\n");
        sb.append("3 - 中度相关（涉及了问题的一部分）\n");
        sb.append("2 - 轻度相关（仅间接涉及）\n");
        sb.append("1 - 不相关（与问题无关）\n\n");

        for (int i = 0; i < batch.size(); i++) {
            String content = batch.get(i).getContent();
            String fileName = batch.get(i).getFileName();
            sb.append("--- 片段 ").append(i + 1).append(" ---\n");
            if (fileName != null && !fileName.isBlank()) {
                sb.append("文件名: ").append(fileName).append("\n");
            }
            sb.append("内容: ").append(content != null ? content : "").append("\n\n");
        }

        sb.append("请返回 JSON 数组，每个元素格式：{\"index\": 片段编号, \"score\": 分数, \"reason\": \"原因\"}\n");
        sb.append("只返回 JSON 数组，不要 markdown 标记。");

        String response;
        try {
            response = fastModel.chat(
                    ChatRequest.builder()
                        .messages(List.of(UserMessage.from(sb.toString())))
                        .build())
                    .aiMessage().text();
        } catch (Exception e) {
            log.warn("LLM scoring call failed: {}", e.getMessage());
            throw e;
        }

        // 解析 JSON 响应
        try {
            String json = response;
            if (json.contains("```json")) {
                json = json.substring(json.indexOf("```json") + 7);
                json = json.substring(0, json.indexOf("```"));
            } else if (json.contains("```")) {
                json = json.substring(json.indexOf("```") + 3);
                json = json.substring(0, json.indexOf("```"));
            }
            json = json.trim();

            List<Map<String, Object>> scores = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, List.class);

            // 归一化到 [0, 1]
            Map<Integer, Double> scoreMap = new HashMap<>();
            for (Map<String, Object> entry : scores) {
                int index = ((Number) entry.get("index")).intValue();
                double score = ((Number) entry.get("score")).doubleValue();
                scoreMap.put(index, (score - 1.0) / 4.0); // 1-5 → 0.0-1.0
            }

            List<Double> result = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                result.add(scoreMap.getOrDefault(i + 1, 0.5));
            }
            return result;

        } catch (Exception e) {
            log.warn("Cross-encoder 响应解析失败，使用默认分 0.5: {}", e.getMessage());
            return batch.stream().map(c -> 0.5).collect(Collectors.toList());
        }
    }
}
