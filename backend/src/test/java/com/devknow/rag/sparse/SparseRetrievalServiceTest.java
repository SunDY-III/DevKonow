package com.devknow.rag.sparse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 稀疏检索单元测试。
 *
 * <p>{@link SparseRetrievalService} 依赖 MySQL ngram 全文索引，
 * 此处测试 {@link SparseEncoder} 单元。
 */
class SparseRetrievalServiceTest {

    @Test
    void sparseEncoder_emptyQuery_shouldReturnEmpty() {
        SparseEncoder encoder = new SparseEncoder(null, null, false);
        SparseVector result = encoder.encode("");
        assertNotNull(result);
        assertTrue(result.getEntries().isEmpty());
    }

    @Test
    void sparseEncoder_disabled_shouldReturnEmpty() {
        SparseEncoder encoder = new SparseEncoder(null, null, false);
        SparseVector result = encoder.encode("test query");
        assertNotNull(result);
        assertTrue(result.getEntries().isEmpty());
    }
}
