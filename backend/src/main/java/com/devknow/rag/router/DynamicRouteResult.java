package com.devknow.rag.router;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 动态路由结果 —— 由 {@link DynamicRouter} 针对单次 query 预测的 RAG 参数。
 *
 * <p>与静态 {@link com.devknow.rag.strategy.ChunkStrategy} 的区别：
 * <ul>
 *   <li>静态：启动时从 YAML 加载，全局不变</li>
 *   <li>动态：每个 query 由 LLM 分类器预测，自适应查询特征</li>
 * </ul>
 *
 * <p>所有字段都有默认值，解析失败时直接使用默认值，
 * 不会导致管道崩溃。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamicRouteResult {

    /** 切分目标长度（字符数），默认 500 */
    @Builder.Default private int chunkSize = 500;

    /** 滑动窗口重叠 */
    @Builder.Default private int chunkOverlap = 80;

    /** HyDE 假设文档生成 */
    @Builder.Default private boolean hydeEnabled = true;

    /** MMR 多样性去重 */
    @Builder.Default private boolean mmrEnabled = true;

    /** 向量检索 TopK */
    @Builder.Default private int vectorTopK = 8;

    /** 关键词检索 TopK */
    @Builder.Default private int keywordTopK = 8;

    /** 重排后保留数 */
    @Builder.Default private int rerankTopN = 4;

    /** Cross-encoder 融合权重 */
    @Builder.Default private double crossEncoderWeight = 0.7;

    /** 候选池大小倍数 */
    @Builder.Default private int candidatePoolMultiplier = 4;

    /** 跳过的检查点列表 */
    @Builder.Default private java.util.List<Integer> skipCheckpoints = java.util.List.of();

    /** 路由理由（供日志和调试） */
    private String reasoning;
}
