package com.devknow.knowledge.graph;

/**
 * 知识图谱文档关系类型。
 *
 * <p>定义了文档之间的四种语义关系，用于 Neo4j 图谱构建和推理。
 */
public enum DocRelationType {

    /** 引用/参考：本文引用了目标文档的内容（如 ADR → 编码规范） */
    REFERENCES,

    /** 依赖/前提：本文依赖于目标文档的决策或设计 */
    DEPENDS_ON,

    /** 扩展/细化：本文对目标文档的原则或规范做了具体化 */
    EXTENDS,

    /** 后续/演进：本文是目标文档的后续更新或演进版本 */
    SEQUEL_TO
}
