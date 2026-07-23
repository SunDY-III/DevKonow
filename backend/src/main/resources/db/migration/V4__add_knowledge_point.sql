-- V4: 知识点表
-- 对应 sql/migration-v6-knowledge-point.sql

CREATE TABLE IF NOT EXISTS knowledge_point (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(255) NOT NULL COMMENT '知识点标题',
    concept TEXT COMMENT '核心概念解释',
    chunk_ids TEXT COMMENT '关联的代码片段 ID 列表（JSON 数组）',
    pattern_name VARCHAR(128) COMMENT '关联的设计模式或技术模式名',
    difficulty_level INT COMMENT '难度等级 L1-L5',
    prerequisite_ids TEXT COMMENT '前置知识点 ID 列表（JSON 数组）',
    related_project_id BIGINT COMMENT '关联项目 ID',
    feynman_pass_count INT DEFAULT 0 COMMENT 'Feynman 检验通过次数',
    review_count INT DEFAULT 0 COMMENT '复习次数',
    source_conversation_id VARCHAR(128) COMMENT '来源对话 ID',
    source_question TEXT COMMENT '来源问题',
    status VARCHAR(32) DEFAULT 'ACTIVE' COMMENT '状态',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_project (related_project_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识点表';
