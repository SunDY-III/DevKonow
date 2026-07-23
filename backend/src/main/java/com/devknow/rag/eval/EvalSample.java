package com.devknow.rag.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 评估样本 —— 一条用户问题 + 期望命中的文档/片段。
 *
 * <p>评估数据集由多条样本组成，用于衡量 RAG 管道的检索质量。
 * 样本可从 JSON 文件加载，也可通过 API 逐条添加。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalSample {

    /** 样本唯一标识 */
    private String id;

    /** 用户问题 */
    private String question;

    /** 期望命中的文档 ID 列表 */
    private List<Long> expectedDocIds;

    /** 期望命中的 Chunk ID 列表（可选，更细粒度） */
    private List<Long> expectedChunkIds;

    /** 期望的路由场景（code / doc / both，用于验证路由准确性） */
    private String expectedScenario;

    /** 知识层级（L1~L5，用于验证层级分类准确性） */
    private Integer expectedLevel;
}
