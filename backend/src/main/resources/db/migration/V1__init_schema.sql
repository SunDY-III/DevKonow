-- V1: 初始表结构
-- 对应 sql/schema.sql（去掉了 CREATE DATABASE / USE）

-- 用户表
CREATE TABLE IF NOT EXISTS sys_user (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  username    VARCHAR(64)  NOT NULL UNIQUE,
  password    VARCHAR(128) NOT NULL COMMENT 'BCrypt 摘要',
  role        VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT 'USER/HANDLER(工单处理人)/ADMIN',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- 知识库文档元数据
CREATE TABLE IF NOT EXISTS knowledge_document (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT       NOT NULL,
  file_name    VARCHAR(255) NOT NULL,
  file_md5     CHAR(32)     NOT NULL COMMENT '幂等去重',
  object_key   VARCHAR(255) NOT NULL COMMENT 'MinIO 对象键',
  status       VARCHAR(16)  NOT NULL DEFAULT 'PARSING' COMMENT 'PARSING/READY/FAILED',
  version      INT          NOT NULL DEFAULT 1 COMMENT '向量版本号',
  deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '软删除',
  chunk_count  INT          NOT NULL DEFAULT 0,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_md5 (file_md5, deleted)
) ENGINE=InnoDB;

-- 文档切分块
CREATE TABLE IF NOT EXISTS document_chunk (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_id      BIGINT      NOT NULL,
  doc_version INT         NOT NULL DEFAULT 1,
  seq         INT         NOT NULL COMMENT '块序号',
  content     TEXT        NOT NULL,
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_doc (doc_id, doc_version),
  FULLTEXT KEY ft_content (content) WITH PARSER ngram
) ENGINE=InnoDB;

-- Token 计费审计
CREATE TABLE IF NOT EXISTS token_usage_log (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT      NOT NULL,
  scene         VARCHAR(32) NOT NULL COMMENT 'CHAT/AGENT/SUMMARY/CLASSIFY/EMBEDDING',
  input_tokens  INT         NOT NULL DEFAULT 0,
  output_tokens INT         NOT NULL DEFAULT 0,
  created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user_time (user_id, created_at)
) ENGINE=InnoDB;

-- 预置工单处理人账号
INSERT IGNORE INTO sys_user(username, password, role) VALUES
 ('handler_a', '$2a$10$N.kmcuVHRJB7g5oZkX9ZUO9C3Hq0eY4F5n5o5o5o5o5o5o5o5o5oW', 'HANDLER'),
 ('handler_b', '$2a$10$N.kmcuVHRJB7g5oZkX9ZUO9C3Hq0eY4F5n5o5o5o5o5o5o5o5o5oW', 'HANDLER');
