package com.devknow.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 幂等服务 —— 基于 Idempotency-Key 的 API 去重。
 *
 * <p>客户端在关键写操作（文档上传、工单创建等）的请求头中传入
 * {@code Idempotency-Key}（UUID），服务端在 Redis 中缓存该 key
 * 对应的处理结果。相同 key 的重复请求直接返回缓存结果，不重复执行。
 *
 * <p>TTL 24 小时：足以覆盖客户端超时重试窗口，同时避免 Redis 堆积。
 */
@Slf4j
@Service
public class IdempotencyService {

    private static final String KEY_PREFIX = "idempotency:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    @Value("${app.idempotency.enabled:true}")
    private boolean enabled;

    public IdempotencyService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 尝试获取幂等锁。
     *
     * @param key 幂等 key（由 IdempotencyFilter 从请求头解析）
     * @return true 表示首次请求（应继续执行），false 表示重复请求（应直接返回）
     */
    public boolean tryAcquire(String key) {
        if (!enabled || key == null || key.isBlank()) return true;
        Boolean absent = redis.opsForValue().setIfAbsent(KEY_PREFIX + key, "processing", TTL);
        return Boolean.TRUE.equals(absent);
    }

    /**
     * 标记幂等 key 为已完成。
     *
     * @param key    幂等 key
     * @param result 处理结果的 JSON 或摘要，重复请求时直接返回
     */
    public void complete(String key, String result) {
        if (!enabled || key == null || key.isBlank()) return;
        try {
            redis.opsForValue().set(KEY_PREFIX + key, result, TTL);
        } catch (Exception e) {
            log.warn("幂等完成标记失败: key={}", key, e);
        }
    }

    /**
     * 获取已缓存的幂等结果。
     *
     * @param key 幂等 key
     * @return 缓存的 JSON 结果，没有缓存时返回 null
     */
    public String getResult(String key) {
        if (!enabled || key == null || key.isBlank()) return null;
        String val = redis.opsForValue().get(KEY_PREFIX + key);
        if (val == null || "processing".equals(val)) return null;
        return val;
    }
}
