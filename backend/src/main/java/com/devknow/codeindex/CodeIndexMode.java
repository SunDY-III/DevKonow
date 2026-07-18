package com.devknow.codeindex;

/**
 * 代码索引模式。
 *
 * <p>通过 {@code app.codeindex.mode} 配置切换，默认使用 Tree-sitter（轻量，零外部依赖）。
 *
 * <ul>
 *   <li>{@link #TREE_SITTER} — 轻量级，零外部依赖，所有语言通用</li>
 *   <li>{@link #SCIP} — 性能级，需要外部 indexer 预生成 index.scip，精度高</li>
 *   <li>{@link #HYBRID} — 混合模式：SCIP 优先（精确调用链），不可用时自动降级 Tree-sitter</li>
 * </ul>
 */
public enum CodeIndexMode {
    TREE_SITTER,
    SCIP,
    HYBRID
}
