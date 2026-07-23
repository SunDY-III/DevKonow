-- migration-v7-context-description.sql
-- Contextual Retrieval：为 document_chunk 表增加上下文描述列
-- 此列存储 LLM 生成的 Chunk 描述，用于嵌入拼接提升检索精度

ALTER TABLE document_chunk
    ADD COLUMN IF NOT EXISTS context_description VARCHAR(500) DEFAULT NULL COMMENT 'Contextual Retrieval 上下文描述（LLM 生成）';
