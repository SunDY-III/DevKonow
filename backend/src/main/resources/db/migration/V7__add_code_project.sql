-- V7: 项目表
-- 对应原始 sql/schema-v2.sql（长期游离在迁移体系外，现正式纳入）

CREATE TABLE IF NOT EXISTS code_project (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  name          VARCHAR(128) NOT NULL UNIQUE,
  display_name  VARCHAR(255) NOT NULL,
  description   TEXT,
  repo_urls     TEXT NOT NULL COMMENT 'JSON 数组',
  language      VARCHAR(64) COMMENT '主语言',
  framework     VARCHAR(128) COMMENT '框架',
  build_tool    VARCHAR(32) COMMENT '构建工具',
  entry_points  TEXT COMMENT '入口点 JSON',
  modules       TEXT COMMENT '模块结构 JSON',
  total_files   INT DEFAULT 0,
  total_methods INT DEFAULT 0,
  status        VARCHAR(32) DEFAULT 'ACTIVE',
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代码项目表';
