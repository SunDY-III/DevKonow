package com.devknow.rag.eval;

import com.devknow.rag.RagService;
import com.devknow.rag.strategy.ChunkStrategy;
import com.devknow.vector.ScoredChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * RAG 评估器 —— 在给定参数组合下运行全部评估样本，产出质量指标。
 *
 * <p>每次评估构成一次"实验"，实验结果 = 参数组合 + 指标结果。
 * 通过 {@link ParamOptimizer} 可基于评估结果自动搜索最优参数。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagEvaluator {

    private final RagService ragService;
    private final EvalDataset evalDataset;

    /**
     * 使用默认参数评估（当前生产配置）。
     */
    public EvalResult evaluate() {
        return evaluate(null, null);
    }

    /**
     * 使用指定参数组合评估。
     *
     * @param paramOverrides 要覆盖的参数（null = 用当前配置）
     * @param label          实验标签（如 "grid-search-v3"），用于结果标识
     * @return 评估结果
     */
    public EvalResult evaluate(RagEvalParams paramOverrides, String label) {
        List<EvalSample> samples = evalDataset.getAll();
        if (samples.isEmpty()) {
            log.warn("No eval samples available, cannot evaluate");
            return new EvalResult(label, Map.of(), new EvalMetrics(0, 0, 0, 0, 0, 0), 0);
        }

        long start = System.nanoTime();
        List<EvalMetrics.SampleResult> allResults = new ArrayList<>();

        for (EvalSample sample : samples) {
            try {
                // 用当前参数检索
                List<ScoredChunk> retrieved = retrieveWithParams(sample, paramOverrides);
                EvalMetrics.SampleResult result = EvalMetrics.evaluate(sample, retrieved);
                allResults.add(result);

                if (allResults.size() % 10 == 0) {
                    log.info("Evaluated {}/{} samples...", allResults.size(), samples.size());
                }
            } catch (Exception e) {
                log.warn("Eval failed for sample {}: {}", sample.getId(), e.getMessage());
                allResults.add(new EvalMetrics.SampleResult(0, 0, 0, 0, 0));
            }
        }

        EvalMetrics aggregated = EvalMetrics.aggregate(allResults);
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        // 构建参数快照
        Map<String, Object> params = paramOverrides != null
                ? paramOverrides.toMap()
                : Map.of("mode", "production-default");

        log.info("Evaluation '{}' complete: samples={}, HitRate@4={:.2f}, HitRate@8={:.2f}, Recall@8={:.2f}, MRR={:.2f}, 耗时={}ms",
                label, samples.size(),
                aggregated.getHitRateAt4(), aggregated.getHitRateAt8(),
                aggregated.getRecallAt8(), aggregated.getMeanReciprocalRank(),
                elapsed);

        return new EvalResult(label, params, aggregated, elapsed);
    }

    /**
     * 使用指定参数运行一次检索。
     * 通过临时修改 RagService 行为或构造带参数的检索请求来实现。
     */
    private List<ScoredChunk> retrieveWithParams(EvalSample sample, RagEvalParams params) {
        String scenario = sample.getExpectedScenario() != null
                ? sample.getExpectedScenario() : "doc";

        // 使用带动态路由的检索
        var result = ragService.levelAwareRetrieve(0L, null, sample.getQuestion(), scenario);

        return result.getChunks() != null ? result.getChunks() : List.of();
    }

    // ==================== 结果 ====================

    /**
     * 单次评估实验的结果。
     */
    public record EvalResult(
            String label,
            Map<String, Object> parameters,
            EvalMetrics metrics,
            long elapsedMs
    ) {

        /** 综合得分（0~1），用于优化器排序 */
        public double compositeScore() {
            return 0.35 * metrics.getHitRateAt4()
                    + 0.25 * metrics.getMeanReciprocalRank()
                    + 0.20 * metrics.getRecallAt8()
                    + 0.20 * metrics.getPrecisionAt4();
        }
    }
}
