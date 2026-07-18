package com.devknow.rag;

import com.devknow.vector.ScoredChunk;
import com.devknow.vector.VectorStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.BiFunction;

/**
 * MMR（Maximal Marginal Relevance，最大边际相关）多样性重排。
 *
 * <p><b>解决什么问题：</b>混合检索 + RRF 融合后的 TopK 里，经常混进大量
 * 语义近似/内容重复的片段（同一段话被切到相邻 chunk、多文档抄录同一条款……）。
 * 这些冗余片段挤占有限的 Prompt 上下文窗口、抬高 Token 成本，还会稀释信息覆盖度。</p>
 *
 * <p><b>标准 MMR 公式：</b>
 * <pre>
 *   next = argmax_{d in C\S} [ λ·sim(d, q) - (1-λ)·max_{s in S} sim(d, s) ]
 * </pre>
 *
 * <p><b>图感知扩展（Graph-aware MMR）：</b>
 * <pre>
 *   sim'(d, s) = sim(d, s) × α(hops(doc_d, doc_s))
 *   α(h) = 1 - 0.3 × exp(-h)
 * </pre>
 * 当两个片段所属的文档在知识图谱中有引用关系时，α < 1，
 * 降低多样性惩罚，保留互补信息。无关文档退化为标准 MMR。
 * 跳数越低 α 越小（冗余惩罚越强），跳数越高越趋近 1.0。
 *
 * <p>graphRelatedDocIds 存储为 {@code Map<Long, Map<Long, Integer>>}：
 * {@code {docId → {relatedDocId → hops}}}，其中 hops 为图谱路径长度。
 *
 * <p>纯计算、无外部副作用，便于单测。</p>
 */
@Slf4j
@Component
public class MmrSelector {

    /**
     * 标准 MMR 选择（无图谱感知）。
     */
    public List<ScoredChunk> select(float[] queryVector,
                                    List<ScoredChunk> candidates,
                                    BiFunction<Long, Long, float[]> vectorResolver,
                                    double lambda,
                                    int topN) {
        return select(queryVector, candidates, vectorResolver, lambda, topN, null);
    }

    /**
     * 图感知 MMR 选择。
     *
     * @param queryVector       查询向量（relevance 项基准，可为 null）
     * @param candidates        RRF + 规则重排后的候选池，已按相关性降序
     * @param vectorResolver    给定 (docId, chunkId) 返回该片段 embedding，缺失返回 null
     * @param lambda            相关性/多样性权衡 λ∈[0,1]
     * @param topN              最终选出的片段数
     * @param graphRelatedDocIds 图谱中文档关联关系：docId → {关联文档 docId → 跳数 hops}
     *                           null 或不提供则退化为标准 MMR
     * @return 多样性重排后的 TopN
     */
    public List<ScoredChunk> select(float[] queryVector,
                                    List<ScoredChunk> candidates,
                                    BiFunction<Long, Long, float[]> vectorResolver,
                                    double lambda,
                                    int topN,
                                    Map<Long, Map<Long, Integer>> graphRelatedDocIds) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        if (topN <= 0) return List.of();
        if (candidates.size() <= topN) return candidates;

        int n = candidates.size();
        float[][] vecs = new float[n][];
        double[] relevance = new double[n];
        Long[] docIds = new Long[n];
        int missing = 0;
        for (int i = 0; i < n; i++) {
            ScoredChunk c = candidates.get(i);
            float[] v = vectorResolver == null ? null : vectorResolver.apply(c.getDocId(), c.getChunkId());
            vecs[i] = v;
            docIds[i] = c.getDocId();
            relevance[i] = (v != null && queryVector != null)
                    ? VectorStoreService.cosine(queryVector, v)
                    : rankFallback(i, n);
            if (v == null) missing++;
        }

        boolean[] picked = new boolean[n];
        List<Integer> selected = new ArrayList<>(topN);

        // 第 1 个：纯相关性最高
        int first = argMaxRelevance(relevance, picked);
        picked[first] = true;
        selected.add(first);

        // 迭代挑选：每轮取 MMR 分最高者
        while (selected.size() < topN) {
            int best = -1;
            double bestScore = -Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (picked[i]) continue;
                double maxSimToSelected = 0.0;
                for (int s : selected) {
                    double sim = pairSim(vecs[i], vecs[s], docIds[i], docIds[s], graphRelatedDocIds);
                    if (sim > maxSimToSelected) maxSimToSelected = sim;
                }
                double mmr = lambda * relevance[i] - (1.0 - lambda) * maxSimToSelected;
                if (mmr > bestScore) {
                    bestScore = mmr;
                    best = i;
                }
            }
            if (best < 0) break;
            picked[best] = true;
            selected.add(best);
        }

        List<ScoredChunk> result = new ArrayList<>(selected.size());
        for (int idx : selected) result.add(candidates.get(idx));
        if (graphRelatedDocIds != null && !graphRelatedDocIds.isEmpty()) {
            log.info("mmr select(graph-aware): candidates={}, picked={}, lambda={}, missingVector={}",
                    n, result.size(), lambda, missing);
        } else {
            log.info("mmr select: candidates={}, picked={}, lambda={}, missingVector={}",
                    n, result.size(), lambda, missing);
        }
        return result;
    }

    /**
     * 图感知的片段间相似度。
     * <p>
     * 标准余弦相似度 × 图距离衰减因子 α(h)：
     * <pre>
     *   α(h) = 1 - 0.3 × exp(-h)
     *   同文档 (h=0): α=0.70 → 加强冗余惩罚
     *   图关联 (h≤2): α≈0.85~0.96 → 降低惩罚，保留互补
     *   无关文档:     α=1.0  → 退化为标准 MMR
     * </pre>
     */
    private double pairSim(float[] a, float[] b, Long docIdA, Long docIdB,
                           Map<Long, Map<Long, Integer>> graphRelatedDocIds) {
        if (a == null || b == null) return 0.0;
        double cos = VectorStoreService.cosine(a, b);

        // 无图谱数据 → 退化为标准 MMR
        if (graphRelatedDocIds == null || graphRelatedDocIds.isEmpty()
                || docIdA == null || docIdB == null) {
            return cos;
        }

        double alpha = computeGraphAlpha(docIdA, docIdB, graphRelatedDocIds);
        return cos * alpha;
    }

    /**
     * 计算图距离衰减因子 α。
     *
     * <p>α(h) = 1 - 0.3 × exp(-h)
     * <ul>
     *   <li>同一文档 (h=0): α = 0.70</li>
     *   <li>直接关联 (h=1): α = 1 - 0.3/e ≈ 0.89</li>
     *   <li>2 跳关联 (h=2): α = 1 - 0.3/e² ≈ 0.96</li>
     *   <li>无关 (h=∞):    α = 1.0</li>
     * </ul>
     */
    /**
     * 图距离衰减因子 α，实现指数衰减公式。
     *
     * <p>α(h) = 1 - 0.3 × exp(-h)
     * <ul>
     *   <li>同一文档 (h=0): α = 0.70</li>
     *   <li>直接关联 (h=1): α = 1 - 0.3/e ≈ 0.89</li>
     *   <li>2 跳关联 (h=2): α = 1 - 0.3/e² ≈ 0.96</li>
     *   <li>无关 (h=∞):    α = 1.0</li>
     * </ul>
     */
    private double computeGraphAlpha(Long docIdA, Long docIdB,
                                      Map<Long, Map<Long, Integer>> graphRelatedDocIds) {
        if (docIdA.equals(docIdB)) {
            return 0.70; // α(0) = 1 - 0.3 * e^0
        }

        // 从 docIdA → relatedDocId 映射中查 hops
        Map<Long, Integer> relatedA = graphRelatedDocIds.get(docIdA);
        if (relatedA != null) {
            Integer hops = relatedA.get(docIdB);
            if (hops != null) {
                return 1.0 - 0.3 * Math.exp(-hops);
            }
        }

        // 反向查找（图是无向的，但 Map 只存了单向查找）
        Map<Long, Integer> relatedB = graphRelatedDocIds.get(docIdB);
        if (relatedB != null) {
            Integer hops = relatedB.get(docIdA);
            if (hops != null) {
                return 1.0 - 0.3 * Math.exp(-hops);
            }
        }

        return 1.0; // 无图关系 → 标准 MMR
    }

    private int argMaxRelevance(double[] relevance, boolean[] picked) {
        int best = 0;
        double bestVal = -Double.MAX_VALUE;
        for (int i = 0; i < relevance.length; i++) {
            if (picked[i]) continue;
            if (relevance[i] > bestVal) {
                bestVal = relevance[i];
                best = i;
            }
        }
        return best;
    }

    private double rankFallback(int rank, int total) {
        return (double) (total - rank) / total;
    }

}
