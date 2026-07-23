package com.devknow.rag.eval;

import com.devknow.vector.ScoredChunk;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 检索质量评估指标。
 *
 * <p>支持指标：
 * <ul>
 *   <li><b>HitRate@K</b>：Top-K 中是否包含至少一个相关文档（0/1）</li>
 *   <li><b>Recall@K</b>：相关文档召回比例</li>
 *   <li><b>MRR</b>：第一个相关文档的排名的倒数</li>
 *   <li><b>Precision@K</b>：Top-K 中相关文档的比例</li>
 * </ul>
 */
@Data
@AllArgsConstructor
public class EvalMetrics {

    /** 样本数 */
    private int sampleCount;

    /** HitRate@4 均值 */
    private double hitRateAt4;

    /** HitRate@8 均值 */
    private double hitRateAt8;

    /** Recall@8 均值 */
    private double recallAt8;

    /** MRR 均值 */
    private double meanReciprocalRank;

    /** Precision@4 均值 */
    private double precisionAt4;

    // ==================== 计算 ====================

    /**
     * 对单条样本的计算单条指标。
     */
    public static SampleResult evaluate(EvalSample sample, List<ScoredChunk> retrieved) {
        Set<Long> expectedDocIds = new HashSet<>(sample.getExpectedDocIds());
        if (expectedDocIds.isEmpty()) {
            return new SampleResult(1.0, 1.0, 1.0, 1.0, 1.0);
        }

        // Top-K 列表
        List<Long> retrievedDocIds = retrieved.stream()
                .map(ScoredChunk::getDocId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (retrievedDocIds.isEmpty()) {
            return new SampleResult(0, 0, 0, 0, 0);
        }

        // HitRate@4
        Set<Long> top4 = retrievedDocIds.size() > 4
                ? new HashSet<>(retrievedDocIds.subList(0, 4))
                : new HashSet<>(retrievedDocIds);
        boolean hit4 = top4.stream().anyMatch(expectedDocIds::contains);

        // HitRate@8
        Set<Long> top8 = retrievedDocIds.size() > 8
                ? new HashSet<>(retrievedDocIds.subList(0, 8))
                : new HashSet<>(retrievedDocIds);
        boolean hit8 = top8.stream().anyMatch(expectedDocIds::contains);

        // Recall@8
        long recalledInTop8 = retrievedDocIds.stream()
                .limit(8)
                .filter(expectedDocIds::contains)
                .count();
        double recall8 = expectedDocIds.size() > 0
                ? (double) recalledInTop8 / expectedDocIds.size()
                : 1.0;

        // MRR
        double mrr = 0;
        for (int i = 0; i < retrievedDocIds.size(); i++) {
            if (expectedDocIds.contains(retrievedDocIds.get(i))) {
                mrr = 1.0 / (i + 1);
                break;
            }
        }

        // Precision@4
        long relevantInTop4 = retrievedDocIds.stream()
                .limit(4)
                .filter(expectedDocIds::contains)
                .count();
        double precision4 = top4.isEmpty() ? 0 : (double) relevantInTop4 / top4.size();

        return new SampleResult(
                hit4 ? 1.0 : 0,
                hit8 ? 1.0 : 0,
                recall8,
                mrr,
                precision4
        );
    }

    /**
     * 聚合多条样本的指标。
     */
    public static EvalMetrics aggregate(List<SampleResult> results) {
        if (results.isEmpty()) {
            return new EvalMetrics(0, 0, 0, 0, 0, 0);
        }

        double hit4Avg = results.stream().mapToDouble(SampleResult::getHitRate4).average().orElse(0);
        double hit8Avg = results.stream().mapToDouble(SampleResult::getHitRate8).average().orElse(0);
        double recall8Avg = results.stream().mapToDouble(SampleResult::getRecall8).average().orElse(0);
        double mrrAvg = results.stream().mapToDouble(SampleResult::getMrr).average().orElse(0);
        double precision4Avg = results.stream().mapToDouble(SampleResult::getPrecision4).average().orElse(0);

        return new EvalMetrics(results.size(), hit4Avg, hit8Avg, recall8Avg, mrrAvg, precision4Avg);
    }

    // ==================== 单条结果 ====================

    @Data
    @AllArgsConstructor
    public static class SampleResult {
        private double hitRate4;
        private double hitRate8;
        private double recall8;
        private double mrr;
        private double precision4;
    }
}
