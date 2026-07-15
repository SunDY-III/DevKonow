-- ============================================
-- RAG 层级感知 + 知识图谱增强
-- Version: 3.0
-- ============================================

-- 1. 文档表追加层级字段
ALTER TABLE knowledge_document
    ADD COLUMN level   INT          DEFAULT 0  COMMENT '知识层级: 0=未分类 1~5',
    ADD COLUMN tags    VARCHAR(1024) DEFAULT NULL COMMENT '标签,逗号分隔',
    ADD COLUMN level_confidence DOUBLE DEFAULT 1.0 COMMENT '人工标记的层级置信度';

-- 2. 层级索引（加速关键词检索时的层级过滤）
ALTER TABLE knowledge_document ADD INDEX idx_level (level);

-- 3. 用户表追加知识职责角色（角色感知 RAG 用）
ALTER TABLE sys_user
    ADD COLUMN knowledge_role VARCHAR(32) DEFAULT NULL COMMENT '知识职责: ARCHITECT/SENIOR_DEV/DEVELOPER/QA/DEVOPS/PM/UNSPECIFIED';
