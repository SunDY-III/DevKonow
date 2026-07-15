package com.devknow.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    /**
     * 关键词召回通道：MySQL ngram 全文索引。
     */
    @Query(value = "SELECT c.* FROM document_chunk c " +
            "JOIN knowledge_document d ON d.id = c.doc_id AND d.deleted = 0 AND d.version = c.doc_version " +
            "WHERE MATCH(c.content) AGAINST(:query IN NATURAL LANGUAGE MODE) " +
            "LIMIT :topK", nativeQuery = true)
    List<DocumentChunk> keywordSearch(@Param("query") String query, @Param("topK") int topK);

    /**
     * 带层级过滤的关键词检索（层级感知 RAG 用）。
     */
    @Query(value = "SELECT c.* FROM document_chunk c " +
            "JOIN knowledge_document d ON d.id = c.doc_id AND d.deleted = 0 AND d.version = c.doc_version " +
            "WHERE d.level IN (:levels) " +
            "AND MATCH(c.content) AGAINST(:query IN NATURAL LANGUAGE MODE) " +
            "LIMIT :topK", nativeQuery = true)
    List<DocumentChunk> keywordSearchByLevel(@Param("query") String query,
                                              @Param("levels") List<Integer> levels,
                                              @Param("topK") int topK);

    /**
     * 取文档的第一个 chunk（用于图谱扩展时获取关联文档的摘要片段）。
     */
    Optional<DocumentChunk> findFirstByDocIdOrderBySeqAsc(Long docId);

    @Modifying
    @Query("delete from DocumentChunk c where c.docId = :docId")
    void deleteByDocId(@Param("docId") Long docId);
}
