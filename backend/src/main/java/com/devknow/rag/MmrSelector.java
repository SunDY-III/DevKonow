package com.devknow.rag;

import com.devknow.vector.ScoredChunk;
import com.devknow.vector.VectorStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * MMR（Maximal Marginal Relevance，最大边际相关）多样性重排。
 *
 * <p><b>解决什么问题（面试点）：</b>混合检索 + RRF 融合后的 TopK 里，经常混进大量
 * 语义近似/内容重复的片段（同一段话被切到相邻 chunk、多文档抄录同一条款……）。
 * 这些冗余片段挤占有限的 Prompt 上下文窗口、抬高 Token 成本，还会稀释信息覆盖度——
 * “越相关的 N 条”不等于“信息量最大的 N 条”。</p>
 *
 * <p><b>MMR 思想：</b>每一步都在「与查询相关」和「与已选片段不冗余」之间做权衡，
 * 迭代地把“相关性高 <i>且</i> 与已选集合最不相似”的片段挑进来：</p>
 * <pre>
 *   next = argmax_{d in C\S} [ λ·sim(d, q) - (1-λ)·max_{s in S} sim(d, s) ]
 * </pre>
 * 其中 C 是候选池、S 是已选集合、q 是查询；λ∈[0,1] 控制相关性/多样性权衡
 * （λ→1 退化为纯相关性排序，λ→0 变为纯多样性）。
 *
 * <p><b>相似度口径：</b>统一用 embedding 余弦。relevance 项用「查询-片段」余弦，
 * diversity 项用「片段-片段」余弦；片段向量从向量库按 docId+chunkId 回查。
 * 关键词通道命中、向量缺失的片段：relevance 退化为候选名次归一值，
 * diversity 视为 0（不因“查不到向量”而被误判为冗余）。</p>
 *
 * <p>纯计算、无外部副作用，便于单测；接口形状与 {@link Reranker} 对齐，可灵活编排。</p>
 */
@Slf4j
@Component
public class MmrSelector {

    /**
     * 在候选池上做 MMR 多样性选择。
     *
     * @param queryVector    查询向量（relevance 项基准，可为 null）
     * @param candidates     RRF + 规则重排后的候选池，已按相关性降序
     * @param vectorResolver 给定 (docId, chunkId) 返回该片段 embedding，缺失返回 null
     * @param lambda         相关性/多样性权衡 λ∈[0,1]
     * @param topN           最终选出的片段数
     * @return 多样性重排后的 TopN（保持挑选顺序）
     */
    public List<ScoredChunk> select(float[] queryVector,
                                    List<ScoredChunk> candidates,
                                    BiFunction<Long, Long, float[]> vectorResolver,
                                    double lambda,
                                    int topN) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        if (topN <= 0) return List.of();
        if (candidates.size() <= topN) return candidates;   // 候选不足，无需筛，直接返回

        int n = candidates.size();
        float[][] vecs = new float[n][];      // 预取候选向量，避免选择循环里重复 IO
        double[] relevance = new double[n];
        int missing = 0;
        for (int i = 0; i < n; i++) {
            ScoredChunk c = candidates.get(i);
            float[] v = vectorResolver == null ? null : vectorResolver.apply(c.getDocId(), c.getChunkId());
            vecs[i] = v;
            relevance[i] = (v != null && queryVector != null)
                    ? VectorStoreService.cosine(queryVector, v)   // 有向量：查询余弦
                    : rankFallback(i, n);                          // 无向量：名次归一兜底
            if (v == null) missing++;
        }

        boolean[] picked = new boolean[n];
        List<Integer> selected = new ArrayList<>(topN);

        // 第 1 个：纯相关性最高（已选集合为空，diversity 项不参与）
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
                    double sim = pairSim(vecs[i], vecs[s]);
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
        log.info("mmr select: candidates={}, picked={}, lambda={}, missingVector={}",
                n, result.size(), lambda, missing);
        return result;
    }

    /** 片段-片段相似度；任一向量缺失则视为 0（不施加冗余惩罚） */
    private double pairSim(float[] a, float[] b) {
        if (a == null || b == null) return 0.0;
        return VectorStoreService.cosine(a, b);
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

    /** 无向量时的 relevance 兜底：候选名次越靠前分越高，线性归一到 (0,1] */
    private double rankFallback(int rank, int total) {
        return (double) (total - rank) / total;
    }
}
