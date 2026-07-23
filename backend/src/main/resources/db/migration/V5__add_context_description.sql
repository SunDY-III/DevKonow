-- V5: Contextual Retrieval 上下文描述列
-- 对应 sql/migration-v7-context-description.sql

ALTER TABLE document_chunk
    ADD COLUMN IF NOT EXISTS context_description VARCHAR(500) DEFAULT NULL COMMENT 'Contextual Retrieval 上下文描述（LLM 生成）';
