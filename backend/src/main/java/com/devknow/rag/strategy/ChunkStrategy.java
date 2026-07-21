package com.devknow.rag.strategy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 场景策略配置 —— 一次冻结、全局生效的参数快照。
 *
 * <p>每个场景（learn / interview / safety / code / doc）对应一个 ChunkStrategy
 * 实例，控制 RAG 全链路的可调参数：文本切分粒度、检查点掩码、HyDE/MMR 开关、
 * 检索深度、Cross-encoder 融合权重等。
 *
 * <p>策略由 {@link RagStrategyRouter} 在启动时从 YAML 配置构建，
 * 运行时通过 {@code RagService.levelAwareRetrieve(..., scenario)} 传入。
 *
 * <p>设计原则：策略是一次构造、只读快照。运行时不修改策略本身，
 * 如需动态调整参数，修改 YAML 后重启或通过配置中心推送。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkStrategy {

    // ======================== 文本切分 ========================

    /** 切分目标长度（字符数） */
    private int chunkSize;

    /** 滑动窗口重叠（字符数），防止关键句被切断在边界 */
    private int chunkOverlap;

    // ======================== 鉴定点掩码 ========================

    /**
     * 需要跳过的鉴定点（检查点）编号列表。
     * <ul>
     *   <li>1 — Chunk 相关性过滤（第一关）</li>
     *   <li>2 — 答案事实验证（第二关）</li>
     *   <li>3 — 逐字引用追溯（第三关）</li>
     * </ul>
     * 空列表表示全部保留（默认）；[1,2] 表示仅跳过第一、二关；
     * [3] 表示跳过引用追溯（不推荐）。
     */
    private List<Integer> skipCheckpoints;

    // ======================== 检索增强开关 ========================

    /**
     * HyDE（假设文档嵌入）开关。
     * true 时先用 LLM 生成假设文档再 embedding 检索，
     * false 时直接用用户问题向量检索。
     */
    private boolean hydeEnabled;

    /**
     * MMR（最大边际相关性）多样性重排开关。
     * true 时对候选结果做多样性排序，减少冗余；
     * false 时直接按相关性排序取 TopN。
     */
    private boolean mmrEnabled;

    // ======================== 检索参数 ========================

    /** 向量检索 TopK */
    private int vectorTopK;

    /** 关键词检索 TopK */
    private int keywordTopK;

    /** 重排后保留进入 Prompt 的片段数 */
    private int rerankTopN;

    /** Cross-encoder 融合权重（默认 0.7），最终分 = crossScore * w + originalScore * (1-w) */
    private double crossEncoderWeight;

    /** 最小最终片段数（探索模式需要更多片段） */
    private int minFinalChunks;

    /** 候选池大小倍数（探索模式需要更大的候选池） */
    private int candidatePoolMultiplier;

    // ======================== 元信息 ========================

    /** 场景名称标识 */
    private String scenario;

    // ======================== 默认工厂 ========================

    /**
     * 返回默认策略，对应「精准模式」(doc 场景) 的经典参数。
     */
    public static ChunkStrategy defaultStrategy() {
        return ChunkStrategy.builder()
                .chunkSize(500)
                .chunkOverlap(80)
                .skipCheckpoints(List.of())
                .hydeEnabled(true)
                .mmrEnabled(true)
                .vectorTopK(8)
                .keywordTopK(8)
                .rerankTopN(4)
                .crossEncoderWeight(0.7)
                .minFinalChunks(4)
                .candidatePoolMultiplier(4)
                .scenario("default")
                .build();
    }

    // ======================== 便捷方法 ========================

    /** 是否应跳过指定编号的检查点 */
    public boolean shouldSkipCheckpoint(int checkpointNumber) {
        return skipCheckpoints != null && skipCheckpoints.contains(checkpointNumber);
    }

    /** 候选池大小 = rerankTopN × multiplier */
    public int candidatePoolSize() {
        return rerankTopN * Math.max(candidatePoolMultiplier, 4);
    }
}
