package com.devknow.rag;

import com.devknow.vector.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RerankerTest {

    private final Reranker reranker = new Reranker(null);

    @Test
    void emptyCandidatesReturnsEmpty() {
        assertTrue(reranker.rerank("查询", List.of(), 5).isEmpty());
    }

    @Test
    void hitCountChangesOrder() {
        var candidates = List.of(
            chunk(1L, 0.5, "今天天气很好"),
            chunk(2L, 0.5, "张三的工单")
        );
        List<ScoredChunk> result = reranker.rerank("张三", candidates, 2);
        // 含有"张三"的 chunk 应排在前面
        assertEquals(2L, result.get(0).getChunkId(),
                "含有搜索词的 chunk 应排首位，实际顺序: "
                        + result.get(0).getChunkId() + " score=" + result.get(0).getScore()
                        + ", " + result.get(1).getChunkId() + " score=" + result.get(1).getScore());
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
        return new ScoredChunk(id, 1L, 0, "test.txt", content, score, "", null);
    }
}
