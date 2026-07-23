package com.devknow.cache;

import com.devknow.agent.tool.SearchContext;
import com.devknow.vector.ScoredChunk;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent 经验缓存 —— 缓存 ReAct Agent 的检索路径和结果。
 *
 * <p>参考 K3 Mooncake 架构（>90% 缓存命中率），当 Agent 遇到与历史查询
 * 特征相似的查询时，直接复用之前验证有效的检索结果，跳过重复的检索循环。
 *
 * <p>与 {@link SemanticCacheService} 的职责划分：
 * <ul>
 *   <li>语义缓存：缓存 question → answer（最终问答对）</li>
 *   <li>Agent 缓存：缓存 (level, route, terms) → (chunks, toolCalls)（检索路径）</li>
 * </ul>
 *
 * <p>缓存命中时，Agent 跳过 search_code/search_doc 等工具调用循环，
 * 直接利用缓存的 chunks 生成最终答案，大幅降低延迟和 Token 消耗。
 */
@Slf4j
@Service
public class AgentExperienceCache {

    private static final String KEY_PREFIX = "agent:exp:";
    private static final String DOC_INDEX_PREFIX = "agent:doc:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${app.agent.cache.enabled:true}")
    private boolean enabled;

    @Value("${app.agent.cache.ttl-hours:24}")
    private long ttlHours;

    @Value("${app.agent.cache.min-tool-calls:2}")
    private int minToolCallsToCache;

    public AgentExperienceCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    // ==================== 写入 ====================

    /**
     * 保存一次 Agent 经验到缓存。
     *
     * @param level      L1~L5 层级
     * @param route      路由分类（code / doc / both）
     * @param question   原始问题
     * @param searchContext 本次 Agent 的检索上下文（含所有 chunks）
     * @param toolCalls  实际发生的工具调用次数
     * @param answer     Agent 生成的最终回答
     */
    public void save(int level, String route, String question,
                      SearchContext searchContext, int toolCalls, String answer) {
        if (!enabled || toolCalls < minToolCallsToCache) return;
        List<ScoredChunk> chunks = searchContext.getAllChunks();
        if (chunks == null || chunks.isEmpty()) return;

        try {
            // 1. 计算 key
            String keyTerms = extractKeyTerms(question, 5);
            String hash = computeHash(level, route, keyTerms);
            String redisKey = KEY_PREFIX + hash;

            // 2. 构建条目
            AgentCacheEntry entry = new AgentCacheEntry();
            entry.setLevel(level);
            entry.setRoute(route);
            entry.setQuestion(question);
            entry.setKeyTerms(keyTerms);
            entry.setChunks(serializeChunks(chunks));
            entry.setToolCalls(toolCalls);
            entry.setAnswer(answer);
            entry.setCreatedAt(System.currentTimeMillis());

            // 3. 写入 Redis
            String json = objectMapper.writeValueAsString(entry);
            redis.opsForValue().set(redisKey, json, Duration.ofHours(ttlHours));

            // 4. 记录来源 docId，支持联动失效
            Set<Long> docIds = chunks.stream()
                    .map(ScoredChunk::getDocId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            for (Long docId : docIds) {
                redis.opsForSet().add(DOC_INDEX_PREFIX + docId, hash);
                redis.expire(DOC_INDEX_PREFIX + docId, Duration.ofHours(ttlHours));
            }

            log.info("Agent经验缓存: hash={}, level={}, route={}, toolCalls={}, chunks={}, 关键词={}",
                    hash, level, route, toolCalls, chunks.size(), keyTerms);

        } catch (Exception e) {
            log.warn("Agent经验缓存写入失败: {}", e.getMessage());
        }
    }

    // ==================== 读取 ====================

    /**
     * 查找缓存的 Agent 经验。
     *
     * @param level    当前查询的层级
     * @param route    当前查询的路由
     * @param question 当前问题
     * @return 缓存条目（命中时），empty 表示未命中
     */
    public Optional<AgentCacheEntry> lookup(int level, String route, String question) {
        if (!enabled) return Optional.empty();

        try {
            String keyTerms = extractKeyTerms(question, 5);
            String hash = computeHash(level, route, keyTerms);
            String redisKey = KEY_PREFIX + hash;

            String json = redis.opsForValue().get(redisKey);
            if (json == null) return Optional.empty();

            AgentCacheEntry entry = objectMapper.readValue(json, AgentCacheEntry.class);
            log.info("Agent经验缓存 HIT: hash={}, level={}, route={}, toolCalls={}, chunks={}",
                    hash, entry.getLevel(), entry.getRoute(),
                    entry.getToolCalls(), entry.getChunks().size());
            return Optional.of(entry);

        } catch (Exception e) {
            log.warn("Agent经验缓存查询失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ==================== 失效 ====================

    /**
     * 知识库文档变更时，清除关联的 Agent 经验缓存。
     */
    public void invalidateByDoc(Long docId) {
        if (docId == null) return;
        try {
            Set<String> hashes = redis.opsForSet().members(DOC_INDEX_PREFIX + docId);
            if (hashes == null || hashes.isEmpty()) return;
            List<String> keys = hashes.stream()
                    .map(h -> KEY_PREFIX + h)
                    .collect(Collectors.toList());
            redis.delete(keys);
            redis.delete(DOC_INDEX_PREFIX + docId);
            log.info("Agent经验缓存失效: {} 条因 docId={}", keys.size(), docId);
        } catch (Exception e) {
            log.warn("Agent经验缓存失效失败: docId={}", docId, e);
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 提取查询的关键词作为缓存签名的一部分。
     * 取前 N 个非停用词。
     */
    private String extractKeyTerms(String question, int maxTerms) {
        if (question == null || question.isBlank()) return "";
        String[] tokens = question.toLowerCase()
                .split("[\\s,，。；;、．.！!？?（）()【】\\[\\]：:\"'']+");
        Set<String> stopWords = Set.of("的", "了", "是", "在", "有", "和",
                "就", "不", "人", "都", "一", "一个", "上", "也", "很",
                "这个", "那个", "什么", "怎么", "如何", "为什么", "是否",
                "the", "a", "an", "is", "are", "was", "were", "be",
                "do", "does", "did", "have", "has", "had",
                "what", "how", "why", "when", "where", "which", "who");

        return Arrays.stream(tokens)
                .filter(t -> t.length() >= 2)
                .filter(t -> !stopWords.contains(t))
                .distinct()
                .limit(maxTerms)
                .collect(Collectors.joining(","));
    }

    /**
     * 计算缓存 hash。
     */
    private String computeHash(int level, String route, String keyTerms) {
        try {
            String input = level + ":" + (route != null ? route : "") + ":" + keyTerms;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            // 取前 16 字符作为 Redis key
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            // 降级：用简单 hash
            return Integer.toHexString((level + ":" + route + ":" + keyTerms).hashCode());
        }
    }

    private List<Map<String, Object>> serializeChunks(List<ScoredChunk> chunks) {
        return chunks.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chunkId", c.getChunkId());
            m.put("docId", c.getDocId());
            m.put("seq", c.getSeq());
            m.put("fileName", c.getFileName());
            m.put("content", c.getContent());
            m.put("score", c.getScore());
            m.put("source", c.getSource());
            m.put("contextDescription", c.getContextDescription());
            return m;
        }).collect(Collectors.toList());
    }

    // ==================== 条目模型 ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentCacheEntry {
        private int level;
        private String route;
        private String question;
        private String keyTerms;
        private List<Map<String, Object>> chunks;
        private int toolCalls;
        private String answer;
        private long createdAt;
        private long hitCount;

        /** 反序列化为 ScoredChunk 列表 */
        public List<ScoredChunk> toChunks() {
            if (chunks == null) return List.of();
            return chunks.stream()
                    .map(m -> new ScoredChunk(
                            safeLong(m.get("chunkId")),
                            safeLong(m.get("docId")),
                            safeInt(m.get("seq")),
                            (String) m.get("fileName"),
                            (String) m.get("content"),
                            safeDouble(m.get("score")),
                            (String) m.get("source"),
                            (String) m.get("contextDescription")))
                    .collect(Collectors.toList());
        }

        private Long safeLong(Object v) {
            if (v instanceof Number) return ((Number) v).longValue();
            return null;
        }
        private Integer safeInt(Object v) {
            if (v instanceof Number) return ((Number) v).intValue();
            return null;
        }
        private Double safeDouble(Object v) {
            if (v instanceof Number) return ((Number) v).doubleValue();
            return 0.0;
        }
    }
}
