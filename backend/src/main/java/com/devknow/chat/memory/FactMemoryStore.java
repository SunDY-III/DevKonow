package com.devknow.chat.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 长期事实记忆存储 —— Redis 持久化的原子事实。
 *
 * <p>每条事实是一个 Redis String，key = {@code fact:{conversationId}:{factId}}。
 * 同一会话的事实 ID 存储在 Set {@code facts:{conversationId}} 中。
 *
 * <h3>事实修正机制</h3>
 * 当新对话推翻已有事实时（如"不用 MySQL 了，改用 PostgreSQL"），
 * 旧事实的 {@code superseded=true}，并关联新事实 ID。
 * 读取时默认过滤 superseded 的事实。
 */
@Slf4j
@Component
public class FactMemoryStore {

    private static final String KEY_PREFIX = "fact:";
    private static final String INDEX_PREFIX = "facts:";
    private static final Duration DEFAULT_TTL = Duration.ofDays(90);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public FactMemoryStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    // ==================== 写入 ====================

    /**
     * 保存一条事实。如 ID 已存在则更新（updatedAt + 内容）。
     */
    public void save(String conversationId, MemoryFact fact) {
        fact.setConversationId(conversationId);
        fact.setUpdatedAt(System.currentTimeMillis());
        String key = redisKey(conversationId, fact.getId());
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(fact), DEFAULT_TTL);
            redis.opsForSet().add(INDEX_PREFIX + conversationId, fact.getId());
        } catch (Exception e) {
            log.warn("Failed to save fact: conversationId={}, factId={}", conversationId, fact.getId(), e);
        }
    }

    /**
     * 批量保存事实。
     */
    public void saveAll(String conversationId, List<MemoryFact> facts) {
        for (MemoryFact fact : facts) {
            save(conversationId, fact);
        }
    }

    /**
     * 修正已有事实：将旧事实标记为 superseded，保存新事实。
     *
     * @return 新事实的 ID
     */
    public String supersede(String conversationId, String oldFactId, MemoryFact newFact) {
        // 读取旧事实
        MemoryFact oldFact = get(conversationId, oldFactId);
        if (oldFact != null) {
            oldFact.setSuperseded(true);
            oldFact.setSupersededById(newFact.getId());
            oldFact.setUpdatedAt(System.currentTimeMillis());
            save(conversationId, oldFact);
        }
        // 保存新事实
        save(conversationId, newFact);
        return newFact.getId();
    }

    // ==================== 读取 ====================

    /**
     * 获取单条事实。
     */
    public MemoryFact get(String conversationId, String factId) {
        String key = redisKey(conversationId, factId);
        String json = redis.opsForValue().get(key);
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, MemoryFact.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize fact: key={}", key, e);
            return null;
        }
    }

    /**
     * 获取某会话的所有有效（非 superseded）事实，按时间降序。
     */
    public List<MemoryFact> getActiveFacts(String conversationId, int limit) {
        Set<String> factIds = redis.opsForSet().members(INDEX_PREFIX + conversationId);
        if (factIds == null || factIds.isEmpty()) return List.of();

        return factIds.stream()
                .map(id -> get(conversationId, id))
                .filter(Objects::nonNull)
                .filter(f -> !f.isSuperseded())
                .sorted(Comparator.comparingLong(MemoryFact::getCreatedAt).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有事实（含 superseded），供调试用。
     */
    public List<MemoryFact> getAll(String conversationId) {
        Set<String> factIds = redis.opsForSet().members(INDEX_PREFIX + conversationId);
        if (factIds == null || factIds.isEmpty()) return List.of();
        return factIds.stream()
                .map(id -> get(conversationId, id))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(MemoryFact::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }

    // ==================== 检索 ====================

    /**
     * 根据关键词检索相关事实（跨所有会话）。
     * 当前实现限于单会话内检索，跨会话搜索需要全局索引（待后续扩展）。
     */
    public List<MemoryFact> search(String query, int limit) {
        return List.of();
    }

    // ==================== 删除 ====================

    /**
     * 删除会话的全部事实。
     */
    public void deleteAll(String conversationId) {
        Set<String> factIds = redis.opsForSet().members(INDEX_PREFIX + conversationId);
        if (factIds != null) {
            List<String> keys = factIds.stream()
                    .map(id -> redisKey(conversationId, id))
                    .collect(Collectors.toList());
            if (!keys.isEmpty()) redis.delete(keys);
        }
        redis.delete(INDEX_PREFIX + conversationId);
    }

    // ==================== 内部 ====================

    private String redisKey(String conversationId, String factId) {
        return KEY_PREFIX + conversationId + ":" + factId;
    }
}
