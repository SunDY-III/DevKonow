package com.devknow.rag;

import com.devknow.vector.ScoredChunk;

import java.util.*;

/**
 * RRF（Reciprocal Rank Fusion）融合多路检索结果。
 *
 * <p>支持任意数量通道（稠密向量 + 稀疏权重 + 关键词 ngram + ...）。
 *
 * <p>公式：score(d) = sum_i 1 / (k + rank_i(d))，k 为平滑常数（通常 60）。
 * 关键性质：只看"排名"不看"分数"，天然规避量纲对齐问题。
 */
public final class RrfFusion {

    private RrfFusion() {}

    /** 两路融合（向后兼容） */
    public static List<ScoredChunk> fuse(List<ScoredChunk> vectorHits, List<ScoredChunk> keywordHits, int k) {
        return fuse(k, vectorHits, keywordHits);
    }

    /**
     * 多路融合。
     *
     * @param k     RRF 平滑常数
     * @param hits  各通道的检索结果（每个通道一个 List）
     * @return 融合后按 RRF 分数降序排列的结果
     */
    @SafeVarargs
    public static List<ScoredChunk> fuse(int k, List<ScoredChunk>... hits) {
        Map<Long, Double> rrfScore = new HashMap<>();
        Map<Long, ScoredChunk> byId = new HashMap<>();

        for (List<ScoredChunk> channel : hits) {
            accumulate(channel, k, rrfScore, byId);
        }

        return rrfScore.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(e -> {
                    ScoredChunk c = byId.get(e.getKey());
                    return new ScoredChunk(c.getChunkId(), c.getDocId(), c.getSeq(),
                            c.getFileName(), c.getContent(), e.getValue(), c.getSource(), null);
                })
                .toList();
    }

    private static void accumulate(List<ScoredChunk> hits, int k,
                                    Map<Long, Double> rrfScore, Map<Long, ScoredChunk> byId) {
        if (hits == null) return;
        for (int rank = 0; rank < hits.size(); rank++) {
            ScoredChunk c = hits.get(rank);
            if (c == null || c.getChunkId() == null) continue;
            rrfScore.merge(c.getChunkId(), 1.0 / (k + rank + 1), Double::sum);
            byId.putIfAbsent(c.getChunkId(), c);
        }
    }
}
