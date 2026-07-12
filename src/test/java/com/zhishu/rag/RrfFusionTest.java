package com.zhishu.rag;

import com.zhishu.vector.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RRF 融合的单元测试。
 * 核心行为：排名倒数融合，不看原始分数，规避两路量纲不一致。
 */
class RrfFusionTest {

    @Test
    void emptyInputsReturnsEmpty() {
        assertTrue(RrfFusion.fuse(List.of(), List.of(), 60).isEmpty());
    }

    @Test
    void onlyVectorHits() {
        var vec = List.of(
            chunk(1L, 0.9),
            chunk(2L, 0.8)
        );
        List<ScoredChunk> result = RrfFusion.fuse(vec, List.of(), 60);
        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getChunkId());  // RRF 排名高
    }

    @Test
    void onlyKeywordHits() {
        var kw = List.of(
            chunk(3L, 0.0),
            chunk(4L, 0.0)
        );
        List<ScoredChunk> result = RrfFusion.fuse(List.of(), kw, 60);
        assertEquals(2, result.size());
        assertEquals(3L, result.get(0).getChunkId());
    }

    @Test
    void bothChannelsFused() {
        var vec = List.of(
            chunk(1L, 0.9),   // 向量通道第一
            chunk(2L, 0.6)    // 向量通道第二
        );
        var kw = List.of(
            chunk(2L, 0.0),   // 关键词通道第一（与 vec 第二相同）
            chunk(3L, 0.0)    // 关键词通道第二
        );
        // chunk2 在两路都是第二 → RRF 累加分更高，应排第一
        List<ScoredChunk> result = RrfFusion.fuse(vec, kw, 60);
        assertEquals(3, result.size());
        assertEquals(2L, result.get(0).getChunkId());  // 两路都有排名 → RRF 分最高
    }

    @Test
    void rrfScoreHigherWhenPresentInBoth() {
        // 一个片段在两路都出现 → RRF 分更高
        var vec = List.of(chunk(1L, 0.5), chunk(2L, 0.4));
        var kw = List.of(chunk(2L, 0.0));  // chunk2 也在关键词中

        List<ScoredChunk> result = RrfFusion.fuse(vec, kw, 60);
        // chunk2: 1/(60+2) + 1/(60+1) ≈ 0.032  >  chunk1: 1/(60+1) ≈ 0.016
        assertEquals(2L, result.get(0).getChunkId());
    }

    private static ScoredChunk chunk(Long id, double score) {
        return new ScoredChunk(id, 1L, 0, "test.txt", "content " + id, score);
    }
}
