-- DevKnow v2 新增表
-- 项目表
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
  status        VARCHAR(16) DEFAULT 'ACTIVE',
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 代码单元表（按方法粒度）
CREATE TABLE IF NOT EXISTS code_unit (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id    BIGINT NOT NULL,
  repo_name     VARCHAR(128) NOT NULL,
  file_path     VARCHAR(512) NOT NULL,
  package_name  VARCHAR(255),
  class_name    VARCHAR(255),
  method_name   VARCHAR(255),
  signature     TEXT,
  comment       TEXT,
  body          MEDIUMTEXT,
  start_line    INT NOT NULL,
  end_line      INT NOT NULL,
  calls         TEXT COMMENT '调用列表 JSON',
  enriched_calls TEXT COMMENT '增强调用链 JSON',
  resolved_type VARCHAR(512) COMMENT '类型全名',
  annotations   TEXT COMMENT '注解列表 JSON',
  language      VARCHAR(16) DEFAULT 'java',
  checksum      CHAR(32) COMMENT '内容 hash',
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_project (project_id),
  KEY idx_file (project_id, file_path(255)),
  KEY idx_method (project_id, method_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Git 提交记录表
CREATE TABLE IF NOT EXISTS git_commit (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id    BIGINT NOT NULL,
  commit_hash   VARCHAR(40) NOT NULL,
  author_name   VARCHAR(128),
  author_email  VARCHAR(255),
  message       TEXT,
  diff_summary  TEXT COMMENT '变更摘要',
  is_incident   TINYINT DEFAULT 0 COMMENT '是否标记为故障',
  committed_at  DATETIME,
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_project (project_id),
  UNIQUE KEY uk_commit (project_id, commit_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 故障记录表
CREATE TABLE IF NOT EXISTS incident_record (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id    BIGINT NOT NULL,
  title         VARCHAR(255) NOT NULL,
  description   TEXT,
  root_cause    TEXT,
  fix_summary   TEXT,
  related_files TEXT COMMENT 'JSON 数组',
  commit_hash   VARCHAR(40),
  severity      VARCHAR(16) DEFAULT 'MAJOR',
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
