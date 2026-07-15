package com.devknow.config.rerank;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * LLM 层级分类结果。
 *
 * <p>由 {@link LevelClassifier} 根据用户问题判断所属知识层级（L1~L5）。
 */
@Data
@AllArgsConstructor
public class LevelResult {
    /** 预测层级：1~5，0 表示无法判断 */
    private int level;
    /** 置信度：0.0 ~ 1.0 */
    private double confidence;
    /** 分类理由 */
    private String reason;
}
