package com.devknow.rag.sparse;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 稀疏向量 —— 由 {@link SparseEncoder} 从文本中提取的带权词表。
 *
 * <p>与稠密向量的区别：
 * <ul>
 *   <li><b>稠密</b>：固定维度（如 1536）的 float 数组，每个维度无明确语义</li>
 *   <li><b>稀疏</b>：动态数量的 (term, weight) 对，每个 term 有明确语义</li>
 * </ul>
 *
 * <p>在 RAG 管道中作为第三路检索信号：
 * 稠密（语义）+ 稀疏（精确匹配）+ 关键词（ngram 模糊）
 */
@Data
@AllArgsConstructor
public class SparseVector {

    /** (term, weight) 对，按 weight 降序排列 */
    private List<Entry> entries;

    @Data
    @AllArgsConstructor
    public static class Entry {
        private String term;
        private double weight;
    }
}
