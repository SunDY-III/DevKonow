package com.devknow.codeindex;

/**
 * 代码索引模式。
 *
 * <p>两种模式互斥，通过 {@code app.codeindex.mode} 配置切换。
 * 默认使用 Tree-sitter（轻量，零外部依赖）。
 */
public enum CodeIndexMode {
    /** Tree-sitter 语法解析（轻量级，内置，所有语言通用） */
    TREE_SITTER,
    /** SCIP 协议索引（性能级，需要外部 indexer 生成 index.scip） */
    SCIP
}
