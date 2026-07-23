-- V3: 方法调用关联表
-- 对应 sql/migration-v5-method-call.sql

CREATE TABLE IF NOT EXISTS code_method_call (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id  BIGINT          NOT NULL,
    caller_file VARCHAR(512)    NOT NULL COMMENT '调用方文件路径',
    method_name VARCHAR(255)    NOT NULL COMMENT '被调用方法名（纯方法名）',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_call (project_id, caller_file, method_name),
    INDEX idx_method (project_id, method_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='方法调用关联表';
