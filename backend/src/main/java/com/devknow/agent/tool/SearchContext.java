package com.devknow.agent.tool;

import com.devknow.vector.ScoredChunk;
import lombok.Getter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具共享上下文 —— 跨多个 {@code @Tool} 方法收集检索结果。
 *
 * <p>在 AiServices 的 ReAct 循环中，每个工具被调用时会将结果中的
 * ScoredChunk 累积到此上下文中。Agent 完成推理后，可从这里获取
 * 所有已检索的片段用于幻觉检查和引用展示。
 */
@Getter
public class SearchContext {

    private final List<ScoredChunk> allChunks = new ArrayList<>();
    private final Set<Long> seenChunkIds = new HashSet<>();

    /**
     * 尝试添加新 chunk，去重后返回 true 表示新增了内容。
     */
    public boolean addChunk(ScoredChunk chunk) {
        if (chunk != null && chunk.getChunkId() != null && seenChunkIds.add(chunk.getChunkId())) {
            allChunks.add(chunk);
            return true;
        }
        return false;
    }

    /**
     * 批量添加 chunk，返回本次新增的数量。
     */
    public int addChunks(List<ScoredChunk> chunks) {
        if (chunks == null) return 0;
        int added = 0;
        for (ScoredChunk c : chunks) {
            if (addChunk(c)) added++;
        }
        return added;
    }
}
