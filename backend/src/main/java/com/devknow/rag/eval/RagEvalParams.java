package com.devknow.rag.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RAG 评估参数组合 —— 控制检索管道的可调参数。
 *
 * <p>与 {@link com.devknow.rag.strategy.ChunkStrategy} 的概念对应，
 * 但独立于 YAML 配置，专用于评估实验的参数调优。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagEvalParams {

    @Builder.Default private int vectorTopK = 8;
    @Builder.Default private int keywordTopK = 8;
    @Builder.Default private int rerankTopN = 4;
    @Builder.Default private double crossEncoderWeight = 0.7;
    @Builder.Default private int rrfK = 60;
    @Builder.Default private double confidenceThreshold = 0.45;

    /** 是否为默认参数集（所有值使用 YAML 配置） */
    @Builder.Default private boolean isDefault = false;

    /**
     * 转为 Map，用于记录和展示。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("vectorTopK", vectorTopK);
        map.put("keywordTopK", keywordTopK);
        map.put("rerankTopN", rerankTopN);
        map.put("crossEncoderWeight", crossEncoderWeight);
        map.put("rrfK", rrfK);
        map.put("confidenceThreshold", confidenceThreshold);
        return map;
    }

    /**
     * 转为简短标签，用于结果标识。
     */
    public String toLabel() {
        return String.format("v%d_k%d_r%d_ce%.1f",
                vectorTopK, keywordTopK, rerankTopN, crossEncoderWeight);
    }
}
