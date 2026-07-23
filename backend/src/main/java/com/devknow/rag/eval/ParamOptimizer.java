package com.devknow.rag.eval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 参数优化器 —— 通过网格搜索/随机搜索找到最优参数组合。
 *
 * <p>在当前评估数据集上运行多次实验，每次使用不同的参数组合，
 * 按综合得分排序返回 Top-N 的参数集。
 *
 * <p>典型的搜索空间：
 * <ul>
 *   <li>vectorTopK: {4, 8, 12}</li>
 *   <li>rerankTopN: {2, 4, 6}</li>
 *   <li>crossEncoderWeight: {0.5, 0.7, 0.9}</li>
 *   <li>rrfK: {30, 60, 100}</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParamOptimizer {

    private final RagEvaluator evaluator;

    /**
     * 执行网格搜索，返回按综合得分降序排列的结果列表。
     */
    public List<RagEvaluator.EvalResult> gridSearch() {
        // 定义搜索空间
        int[] vectorTopKValues = {4, 8, 12};
        int[] rerankTopNValues = {2, 4, 6};
        double[] ceWeightValues = {0.5, 0.7, 0.9};

        List<RagEvalParams> paramGrid = new ArrayList<>();

        // 先加入当前默认参数作为 baseline
        paramGrid.add(RagEvalParams.builder().isDefault(true).build());

        // 生成所有组合
        for (int v : vectorTopKValues) {
            for (int r : rerankTopNValues) {
                for (double ce : ceWeightValues) {
                    // 跳过无效组合
                    if (r > v) continue; // rerankTopN 不能大于 vectorTopK

                    paramGrid.add(RagEvalParams.builder()
                            .vectorTopK(v)
                            .keywordTopK(v) // keyword 与 vector 保持一致
                            .rerankTopN(r)
                            .crossEncoderWeight(ce)
                            .rrfK(60)
                            .confidenceThreshold(0.45)
                            .build());
                }
            }
        }

        log.info("ParamOptimizer: 共 {} 组参数待评估", paramGrid.size());

        // 依次评估
        List<RagEvaluator.EvalResult> results = new ArrayList<>();
        for (RagEvalParams params : paramGrid) {
            String label = params.isDefault() ? "baseline" : params.toLabel();
            log.info("评估参数: {} → {}", label, params.toMap());

            RagEvaluator.EvalResult result = evaluator.evaluate(params, label);
            results.add(result);

            log.info("  得分: composite={:.4f}, HitRate@4={:.2f}, MRR={:.2f}",
                    result.compositeScore(),
                    result.metrics().getHitRateAt4(),
                    result.metrics().getMeanReciprocalRank());
        }

        // 按综合得分降序排序
        results.sort(Comparator.comparingDouble(RagEvaluator.EvalResult::compositeScore).reversed());

        log.info("ParamOptimizer 完成: ===== 排名 =====");
        for (int i = 0; i < Math.min(results.size(), 5); i++) {
            var r = results.get(i);
            log.info("  #{}. {} → composite={:.4f}, HitRate@4={:.2f}, Recall@8={:.2f}",
                    i + 1, r.label(), r.compositeScore(),
                    r.metrics().getHitRateAt4(), r.metrics().getRecallAt8());
        }

        return results;
    }

    /**
     * 获取最优参数（综合得分最高的组合）。
     */
    public Optional<RagEvaluator.EvalResult> findBest() {
        List<RagEvaluator.EvalResult> results = gridSearch();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
