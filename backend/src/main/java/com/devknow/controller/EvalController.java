package com.devknow.controller;

import com.devknow.rag.eval.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * RAG 评估与参数优化 API。
 *
 * <p>用于衡量 RAG 检索质量、自动搜索最优参数、管理参数版本。
 * 所有端点需 ADMIN 权限（由 SecurityConfig 控制）。
 */
@Slf4j
@RestController
@RequestMapping("/api/eval")
@RequiredArgsConstructor
public class EvalController {

    private final RagEvaluator ragEvaluator;
    private final ParamOptimizer paramOptimizer;
    private final ParamSnapshot paramSnapshot;
    private final EvalDataset evalDataset;

    /**
     * 用当前生产参数执行一次评估。
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runEvaluation() {
        RagEvaluator.EvalResult result = ragEvaluator.evaluate(null, "manual-run");
        paramSnapshot.save(result);
        return ResponseEntity.ok(Map.of(
                "label", result.label(),
                "compositeScore", String.format("%.4f", result.compositeScore()),
                "hitRate@4", String.format("%.2f", result.metrics().getHitRateAt4()),
                "hitRate@8", String.format("%.2f", result.metrics().getHitRateAt8()),
                "recall@8", String.format("%.2f", result.metrics().getRecallAt8()),
                "mrr", String.format("%.2f", result.metrics().getMeanReciprocalRank()),
                "precision@4", String.format("%.2f", result.metrics().getPrecisionAt4()),
                "sampleCount", result.metrics().getSampleCount(),
                "elapsedMs", result.elapsedMs()
        ));
    }

    /**
     * 执行网格搜索，寻找最优参数。
     */
    @PostMapping("/optimize")
    public ResponseEntity<Map<String, Object>> optimize() {
        List<RagEvaluator.EvalResult> results = paramOptimizer.gridSearch();
        // 保存所有结果
        results.forEach(paramSnapshot::save);

        RagEvaluator.EvalResult best = results.isEmpty() ? null : results.get(0);
        return ResponseEntity.ok(Map.of(
                "totalExperiments", results.size(),
                "bestLabel", best != null ? best.label() : "N/A",
                "bestCompositeScore", best != null ? String.format("%.4f", best.compositeScore()) : "N/A",
                "bestParams", best != null ? best.parameters() : Map.of(),
                "ranking", results.stream()
                        .limit(5)
                        .map(r -> Map.of(
                                "label", r.label(),
                                "compositeScore", String.format("%.4f", r.compositeScore()),
                                "params", r.parameters()
                        ))
                        .toList()
        ));
    }

    /**
     * 列出所有参数快照。
     */
    @GetMapping("/snapshots")
    public ResponseEntity<List<Map<String, Object>>> listSnapshots() {
        List<String> labels = paramSnapshot.listSnapshots();
        List<Map<String, Object>> snapshots = labels.stream()
                .map(label -> {
                    var result = paramSnapshot.get(label);
                    if (result == null) return Map.<String, Object>of("label", label, "status", "expired");
                    return Map.<String, Object>of(
                            "label", label,
                            "compositeScore", String.format("%.4f", result.compositeScore()),
                            "hitRate@4", String.format("%.2f", result.metrics().getHitRateAt4()),
                            "sampleCount", result.metrics().getSampleCount()
                    );
                })
                .toList();
        return ResponseEntity.ok(snapshots);
    }

    /**
     * 获取评估数据集状态。
     */
    @GetMapping("/dataset")
    public ResponseEntity<Map<String, Object>> datasetStatus() {
        List<EvalSample> samples = evalDataset.getAll();
        return ResponseEntity.ok(Map.of(
                "totalSamples", samples.size(),
                "samples", samples.stream()
                        .map(s -> Map.of("id", s.getId(), "question", s.getQuestion()))
                        .toList()
        ));
    }

    /**
     * 添加评估样本。
     */
    @PostMapping("/dataset")
    public ResponseEntity<Map<String, Object>> addSample(@RequestBody EvalSample sample) {
        evalDataset.addSample(sample);
        return ResponseEntity.ok(Map.of(
                "status", "added",
                "totalSamples", evalDataset.size()
        ));
    }
}
