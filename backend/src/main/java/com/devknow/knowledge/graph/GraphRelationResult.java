package com.devknow.knowledge.graph;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 图谱关系查询结果。
 */
@Data
@AllArgsConstructor
public class GraphRelationResult {
    private Long docId;
    private String title;
    private int level;
    private String relationType;
    private int hops;
}
