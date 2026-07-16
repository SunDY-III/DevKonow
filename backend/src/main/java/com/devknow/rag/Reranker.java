package com.devknow.rag;

import com.devknow.vector.ScoredChunk;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 多因子重排序：RRF 分基础上 + 查询覆盖率 + 位置加权。
 *
 * <p>公式：
 * <pre>
 * final_score = 0.6 × original_score
 *             + 0.3 × query_coverage（查询词在 chunk 中的命中比例）
 *             + 0.1 × position_bonus（靠前的片段加小分）
 * </pre>
 */
@Component
public class Reranker {

    private static final double SCORE_WEIGHT = 0.6;
    private static final double COVERAGE_WEIGHT = 0.3;
    private static final double POSITION_WEIGHT = 0.1;

    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topN) {
        List<String> queryTerms = normalize(query);

        return candidates.stream()
                .map(c -> {
                    double original = c.getScore();
                    double coverage = computeCoverage(c.getContent(), queryTerms);
                    double positionBonus = computePositionBonus(c.getSeq());
                    double finalScore = SCORE_WEIGHT * original
                            + COVERAGE_WEIGHT * coverage
                            + POSITION_WEIGHT * positionBonus;
                    return new ScoredChunk(c.getChunkId(), c.getDocId(), c.getSeq(),
                            c.getFileName(), c.getContent(),
                            Math.round(finalScore * 10000.0) / 10000.0,
                            c.getSource());
                })
                .sorted(Comparator.comparingDouble(ScoredChunk::getScore).reversed())
                .limit(topN)
                .toList();
    }

    /** 查询词在 chunk 中的命中比例 0~1 */
    private double computeCoverage(String content, List<String> queryTerms) {
        if (queryTerms.isEmpty() || content == null || content.isEmpty()) return 0;
        String lowerContent = content.toLowerCase();
        long hits = queryTerms.stream().filter(lowerContent::contains).count();
        return (double) hits / queryTerms.size();
    }

    /** 位置加分：seq 越小越靠前，0~0.2 */
    private double computePositionBonus(Integer seq) {
        if (seq == null || seq <= 0) return 0;
        // 前 10 段有加分，第 1 段最高 0.2，之后递减
        double bonus = 0.2 * Math.max(0, 1.0 - (seq - 1) / 10.0);
        return Math.round(bonus * 100.0) / 100.0;
    }

    /** 中文+英文分词：按空格/标点切 + 二元 ngram */
    private List<String> normalize(String query) {
        if (query == null || query.isBlank()) return List.of();
        String q = query.toLowerCase().replaceAll("[\\s,，。；;、．.！!？?（）()【】\\[\\]：:]+", "");
        if (q.length() <= 2) return List.of(q);
        java.util.List<String> terms = new java.util.ArrayList<>();
        for (int i = 0; i + 2 <= q.length(); i++) {
            terms.add(q.substring(i, i + 2));
        }
        return terms;
    }
}
