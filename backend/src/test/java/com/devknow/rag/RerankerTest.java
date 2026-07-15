package com.devknow.rag;

import com.devknow.vector.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规则重排序的单元测试。
 * 核心行为：RRF 分基础上 + 查询词命中数加权，再取 TopN。
 */
class RerankerTest {

    private final Reranker reranker = new Reranker();

    @Test
    void emptyCandidatesReturnsEmpty() {
        assertTrue(reranker.rerank("查询", List.of(), 5).isEmpty());
    }

    @Test
    void hitCountBoostsScore() {
        var candidates = List.of(
            chunk(1L, 0.5, "今天天气很好"),
            chunk(2L, 0.5, "张三的工单")
        );
        // "张三" 应该命中第二个
        List<ScoredChunk> result = reranker.rerank("张三", candidates, 2);
        assertEquals(2L, result.get(0).getChunkId());
        assertTrue(result.get(0).getScore() > 0.5);
    }

    @Test
    void topNLimitsResultSize() {
        var candidates = List.of(
            chunk(1L, 0.9, "aaa bbb ccc"),
            chunk(2L, 0.8, "ddd eee fff"),
            chunk(3L, 0.7, "ggg hhh iii")
        );
        assertEquals(2, reranker.rerank("查询", candidates, 2).size());
    }

    private static ScoredChunk chunk(Long id, double score, String content) {
        return new ScoredChunk(id, 1L, 0, "test.txt", content, score);
    }
}
