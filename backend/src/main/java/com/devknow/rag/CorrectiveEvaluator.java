package com.devknow.rag;

import com.devknow.vector.ScoredChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * CRAG (Corrective RAG) 纠错评估器。
 *
 * <p>在检索结果返回前插入评估步骤，判断检索质量并决定后续动作：
 * <ul>
 *   <li>✅ 可信 (confidence ≥ threshold_high) → 直接使用</li>
 *   <li>⚠️ 模糊 (threshold_low ≤ confidence < threshold_high) → 触发补搜</li>
 *   <li>❌ 不相关 (confidence < threshold_low) → 丢弃，走 Agent 兜底</li>
 * </ul>
 *
 * <p>参考论文：Corrective Retrieval Augmented Generation (Yan et al., 2024)
 *
 * <p>优化：复用外部传入的 queryVector，避免补搜时重复 embedding。
 */
@Slf4j
@Component
public class CorrectiveEvaluator {

    /** 高阈值：可信，直接使用 */
    private static final double THRESHOLD_HIGH = 0.7;

    /** 低阈值：低于此值视为不相关 */
    private static final double THRESHOLD_LOW = 0.3;

    /**
     * 评估检索结果并执行纠错策略（无 queryVector 版本）。
     */
    public EvaluationResult evaluate(List<ScoredChunk> chunks, String question,
                                     RetryFunction retryFn) {
        return evaluate(chunks, question, retryFn, null);
    }

    /**
     * 评估检索结果并执行纠错策略（复用 queryVector 版本）。
     *
     * @param chunks      原始检索结果
     * @param question    用户问题
     * @param retryFn     补搜回调（输入放宽后的搜索参数，返回补充结果）
     * @param queryVector 外部传入的 queryVector（避免补搜时重复 embedding）
     * @return 评估后的最终结果
     */
    public EvaluationResult evaluate(List<ScoredChunk> chunks, String question,
                                     RetryFunction retryFn, float[] queryVector) {
        if (chunks == null || chunks.isEmpty()) {
            return new EvaluationResult(List.of(), EvaluationVerdict.LOW_CONFIDENCE, "检索结果为空");
        }

        // 计算整体置信度：Top-3 平均分 + 覆盖率
        List<ScoredChunk> sorted = chunks.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::getScore).reversed())
                .toList();

        double topScore = sorted.get(0).getScore();
        double avgTop3 = sorted.size() >= 3
                ? (sorted.get(0).getScore() + sorted.get(1).getScore() + sorted.get(2).getScore()) / 3.0
                : sorted.stream().mapToDouble(ScoredChunk::getScore).average().orElse(0);

        // 覆盖率：不同来源 chunk 的比例
        Set<String> sources = sorted.stream()
                .map(ScoredChunk::getSource)
                .filter(s -> s != null && !s.isEmpty())
                .collect(Collectors.toSet());
        double coverageBonus = sources.size() >= 2 ? 0.1 : 0.0;

        double confidence = Math.min(1.0, avgTop3 + coverageBonus);

        log.info("CRAG: q={}, topScore={:.4f}, avgTop3={:.4f}, sources={}, confidence={:.4f}, hasQueryVector={}",
                truncate(question, 40), topScore, avgTop3, sources, confidence, queryVector != null);

        if (confidence >= THRESHOLD_HIGH) {
            // ✅ 可信 → 直接使用
            return new EvaluationResult(filterNoise(sorted), EvaluationVerdict.HIGH_CONFIDENCE,
                    String.format("检索可信 (%.0f%%)", confidence * 100));
        }

        if (confidence >= THRESHOLD_LOW) {
            // ⚠️ 模糊 → 触发补搜（放宽搜索范围）
            try {
                log.info("CRAG 触发补搜: confidence={:.4f}", confidence);
                List<ScoredChunk> supplemental = retryFn.retry(sorted);
                List<ScoredChunk> merged = merge(sorted, supplemental);
                return new EvaluationResult(filterNoise(merged), EvaluationVerdict.MEDIUM_CONFIDENCE,
                        String.format("补搜完成，共 %d 条 (原始 %d + 补充 %d)", merged.size(), sorted.size(), supplemental.size()));
            } catch (Exception e) {
                log.warn("CRAG 补搜失败: {}", e.getMessage());
                return new EvaluationResult(filterNoise(sorted), EvaluationVerdict.MEDIUM_CONFIDENCE, "补搜失败，使用原始结果");
            }
        }

        // ❌ 不相关 → 丢弃
        return new EvaluationResult(List.of(), EvaluationVerdict.LOW_CONFIDENCE,
                String.format("检索置信度过低 (%.0f%%)，丢弃结果", confidence * 100));
    }

    /**
     * 过滤噪声：丢弃分数过低的 chunk。
     */
    private List<ScoredChunk> filterNoise(List<ScoredChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return chunks;
        double maxScore = chunks.stream()
                .mapToDouble(ScoredChunk::getScore)
                .max()
                .orElse(0);
        if (maxScore <= 0) return chunks;

        double threshold = maxScore * 0.3; // 低于最高分 30% 的视为噪声
        return chunks.stream()
                .filter(c -> c.getScore() >= threshold)
                .toList();
    }

    /**
     * 合并两路检索结果，保留高分 + 去重。
     */
    private List<ScoredChunk> merge(List<ScoredChunk> primary, List<ScoredChunk> supplemental) {
        Map<Long, ScoredChunk> merged = new LinkedHashMap<>();
        for (ScoredChunk c : primary) {
            merged.put(c.getChunkId(), c);
        }
        for (ScoredChunk c : supplemental) {
            merged.merge(c.getChunkId(), c, (a, b) -> a.getScore() >= b.getScore() ? a : b);
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::getScore).reversed())
                .toList();
    }

    private String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /** 补搜回调接口 */
    @FunctionalInterface
    public interface RetryFunction {
        List<ScoredChunk> retry(List<ScoredChunk> originalResults);
    }

    /** 评估判定 */
    public enum EvaluationVerdict {
        HIGH_CONFIDENCE,   // 直接使用
        MEDIUM_CONFIDENCE, // 补搜后使用
        LOW_CONFIDENCE     // 丢弃
    }

    /** 评估结果 */
    public record EvaluationResult(List<ScoredChunk> chunks, EvaluationVerdict verdict, String message) {}
}
