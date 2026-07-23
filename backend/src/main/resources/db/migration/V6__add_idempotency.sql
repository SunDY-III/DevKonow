-- V6: 幂等性支持
-- 1. 消息消费幂等表（RabbitMQ 消费者防重复处理）
-- 2. Idempotency-Key 存储在 Redis（无需表）

CREATE TABLE IF NOT EXISTS processed_events (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id    VARCHAR(128) NOT NULL COMMENT '消息唯一 ID（结合 deliveryTag 或 messageId）',
    event_type  VARCHAR(64)  NOT NULL COMMENT '事件类型（如 doc_parse）',
    handled_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_event (event_id, event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息消费幂等表';
