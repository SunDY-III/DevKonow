package com.devknow.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息消费幂等工具 —— 防止 RabbitMQ 消费者重复处理同一条消息。
 *
 * <p>RabbitMQ 的 manual ack 模式下，消费者崩溃或超时会导致消息重新投递。
 * 使用 {@code processed_events} 表记录已处理的消息 ID，重复消息直接跳过。
 *
 * <p>使用方式：
 * <pre>{@code
 * if (!deduplicator.tryProcess(docId.toString(), "doc_parse")) {
 *     channel.basicAck(tag, false); // 已处理过，直接确认
 *     return;
 * }
 * try {
 *     // ... 业务逻辑 ...
 *     deduplicator.markCompleted(docId.toString(), "doc_parse");
 *     channel.basicAck(tag, false);
 * } catch (Exception e) {
 *     deduplicator.markFailed(docId.toString(), "doc_parse");
 *     channel.basicNack(tag, false, false);
 * }
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageDeduplicator {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 尝试标记消息为"处理中"。
     *
     * @param eventId   消息唯一 ID（如 docId）
     * @param eventType 事件类型（如 "doc_parse"）
     * @return true = 首次处理（应继续执行业务），false = 已处理过或正在处理
     */
    @Transactional
    public boolean tryProcess(String eventId, String eventType) {
        try {
            int updated = jdbcTemplate.update(
                    "INSERT IGNORE INTO processed_events (event_id, event_type, handled_at) VALUES (?, ?, NOW())",
                    eventId, eventType);
            if (updated > 0) {
                log.debug("消息首次处理: eventId={}, type={}", eventId, eventType);
                return true;
            }
            // INSERT IGNORE 影响行数为 0 → 已存在（重复消息）
            log.info("消息重复投递已跳过: eventId={}, type={}", eventId, eventType);
            return false;
        } catch (DataIntegrityViolationException e) {
            // 唯一约束冲突 → 重复消息
            log.info("消息重复投递已跳过（唯一约束）: eventId={}, type={}", eventId, eventType);
            return false;
        }
    }

    /**
     * 检查消息是否已处理过（只读，不写入）。
     */
    public boolean isProcessed(String eventId, String eventType) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id = ? AND event_type = ?",
                Integer.class, eventId, eventType);
        return count != null && count > 0;
    }
}
