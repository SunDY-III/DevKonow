package com.devknow.rag;

import com.devknow.vector.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
        assertEquals(1L, result.get(0).getChunkId());
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
            chunk(1L, 0.9),
            chunk(2L, 0.6)
        );
        var kw = List.of(
            chunk(2L, 0.0),
            chunk(3L, 0.0)
        );
        List<ScoredChunk> result = RrfFusion.fuse(vec, kw, 60);
        assertEquals(3, result.size());
        assertEquals(2L, result.get(0).getChunkId());
    }

    @Test
    void rrfScoreHigherWhenPresentInBoth() {
        var vec = List.of(chunk(1L, 0.5), chunk(2L, 0.4));
        var kw = List.of(chunk(2L, 0.0));

        List<ScoredChunk> result = RrfFusion.fuse(vec, kw, 60);
        assertEquals(2L, result.get(0).getChunkId());
    }

    private static ScoredChunk chunk(Long id, double score) {
        return new ScoredChunk(id, 1L, 0, "test.txt", "content " + id, score, "", null);
    }
}
