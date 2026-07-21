package com.devknow.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.devknow.vector.QdrantClientManager;
import com.devknow.vector.ScoredChunk;
import com.devknow.vector.VectorStoreService;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

/**
 * 语义缓存：相似问题（向量相似度 >= 阈值）直接命中历史回答，省 Token、降延迟。
 *
 * <p>查找使用 Qdrant 近似度搜索替代 Redis SCAN 全量遍历：将缓存的 query 向量存入 Qdrant
 * <code>cache_vectors</code> collection，查找时走 ANN 索引 O(log n)，而非 SCAN O(n)。
 *
 * <p>阈值 0.95（偏保守）：宁可漏命中走一次 LLM，也不要误命中答非所问。
 * Cache-Aside 模式：写入时 500ms 异步回填，不阻塞主流程。
 *
 * <p>三个设计点（面试点）：
 * <ol>
 *   <li>阈值 0.95：偏保守策略，降低误命中率；</li>
 *   <li>Cache-Aside 异步写入：主流程不等待缓存回填，500ms 超时后降级；</li>
 *   <li>失效联动：缓存条目记录其依据的 docId 列表，知识库按文档维度变更时定向清除，
 *       避免"文档已更新、缓存还在答旧内容"。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticCacheService {

    private static final String KEY_PREFIX = "sem:cache:";
    private static final String CACHE_COLLECTION = "cache_vectors";
    private static final int QDRANT_SEARCH_LIMIT = 10;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QdrantClientManager qdrantManager;

    @Value("${app.semantic-cache.threshold:0.95}") private double threshold;
    @Value("${app.semantic-cache.ttl-hours}") private long ttlHours;

    @Data
    @NoArgsConstructor
    public static class CacheEntry {
        private String question;
        private String answer;
        private float[] vector;
        private List<Long> sourceDocIds;
    }

    /**
     * 语义缓存查找：优先用 Qdrant ANN 索引，降级到 Redis SCAN。
     */
    public Optional<CacheEntry> lookup(float[] queryVector) {
        // 策略 1：Qdrant ANN 搜索（O(log n)，推荐路径）
        QdrantClient qdrant = qdrantManager.getClient();
        if (qdrant != null) {
            try {
                List<Float> vectorList = new ArrayList<>(queryVector.length);
                for (float v : queryVector) vectorList.add(v);

                Points.SearchPoints.Builder builder = Points.SearchPoints.newBuilder()
                        .setCollectionName(CACHE_COLLECTION)
                        .addAllVector(vectorList)
                        .setLimit(QDRANT_SEARCH_LIMIT)
                        .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build());

                List<Points.ScoredPoint> results = qdrant.searchAsync(builder.build())
                        .get(3, TimeUnit.SECONDS);

                for (Points.ScoredPoint result : results) {
                    if (result.getScore() >= threshold) {
                        String redisKey = result.getPayloadOrDefault("redis_key", value("")).getStringValue();
                        if (!redisKey.isEmpty()) {
                            String json = redis.opsForValue().get(redisKey);
                            if (json != null) {
                                try {
                                    CacheEntry entry = objectMapper.readValue(json, CacheEntry.class);
                                    log.info("semantic cache HIT (Qdrant), score={:.4f}, key={}",
                                            result.getScore(), redisKey);
                                    return Optional.of(entry);
                                } catch (Exception ignored) {}
                            } else {
                                // 惰性删除：Redis 条目已过期，清理 Qdrant 中的孤立向量
                                try {
                                    qdrant.deleteAsync(CACHE_COLLECTION, List.of(result.getId()))
                                            .get(1, TimeUnit.SECONDS);
                                    log.debug("清理孤立 Qdrant 缓存向量: pointId={}", result.getId());
                                } catch (Exception ex) {
                                    log.debug("清理孤立 Qdrant 向量失败: {}", ex.getMessage());
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Qdrant 语义缓存查询失败，降级到 Redis SCAN: {}", e.getMessage());
            }
        }

        // 策略 2：Redis SCAN 降级（兼容路径，与旧缓存兼容）
        return lookupViaRedis(queryVector);
    }

    /**
     * Redis SCAN 降级查找（保留原实现作为兼容）。
     */
    private Optional<CacheEntry> lookupViaRedis(float[] queryVector) {
        CacheEntry best = null;
        double bestScore = 0;
        int scanned = 0;
        int maxScan = 500;
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(100).build())) {
            while (cursor.hasNext() && scanned < maxScan) {
                String json = redis.opsForValue().get(cursor.next());
                scanned++;
                if (json == null) continue;
                try {
                    CacheEntry e = objectMapper.readValue(json, CacheEntry.class);
                    double score = VectorStoreService.cosine(queryVector, e.getVector());
                    if (score > bestScore) { bestScore = score; best = e; }
                    if (score > 0.99) break;
                } catch (Exception ignored) { }
            }
        }
        if (scanned >= maxScan) {
            log.warn("semantic cache scan reached limit {}", maxScan);
        }
        if (best != null && bestScore >= threshold) {
            log.info("semantic cache HIT (Redis fallback), score={:.4f}, q={}",
                    bestScore, best.getQuestion());
            return Optional.of(best);
        }
        return Optional.empty();
    }

    /**
     * Cache-Aside 写入：异步回填缓存，500ms 超时。
     * 主流程不等待缓存写入完成。
     */
    @SneakyThrows
    public void put(String question, String answer, float[] vector, List<Long> sourceDocIds) {
        CacheEntry e = new CacheEntry();
        e.setQuestion(question);
        e.setAnswer(answer);
        e.setVector(vector);
        e.setSourceDocIds(sourceDocIds);

        // Redis：完整条目存储（含 TTL）
        String redisKey = KEY_PREFIX + Math.abs(question.hashCode()) + ":" + System.currentTimeMillis();
        redis.opsForValue().set(redisKey,
                objectMapper.writeValueAsString(e), Duration.ofHours(ttlHours));

        // Cache-Aside：异步写入 Qdrant（不阻塞主流程）
        QdrantClient qdrant = qdrantManager.getClient();
        if (qdrant != null) {
            try {
                long pointId = (long) redisKey.hashCode();
                float[] vec = e.getVector();
                List<Float> qVec = new ArrayList<>(vec.length);
                for (float v : vec) qVec.add(v);

                // 500ms 超时：不等 Qdrant 写入完成，不阻塞主流程
                var pointBuilder = Points.PointStruct.newBuilder()
                        .setId(id(pointId))
                        .setVectors(vectors(qVec))
                        .putPayload("redis_key", value(redisKey));
                // 将 sourceDocIds 写入 Qdrant payload，支持按文档清理向量
                if (sourceDocIds != null && !sourceDocIds.isEmpty()) {
                    for (Long docId : sourceDocIds) {
                        pointBuilder.putPayload("doc_" + docId, value("1"));
                    }
                }
                qdrant.upsertAsync(CACHE_COLLECTION, List.of(pointBuilder.build()))
                        .get(500, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                log.debug("Qdrant 缓存写入超时（预期内，Cache-Aside 降级）");
            } catch (Exception ex) {
                log.warn("Qdrant 缓存向量写入失败: {}", ex.getMessage());
            }
        }
    }

    /** 知识库文档变更 -> 清除依据该文档生成的缓存条目 */
    public void invalidateByDoc(Long docId) {
        int deleted = 0;
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(200).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String json = redis.opsForValue().get(key);
                if (json == null) continue;
                try {
                    CacheEntry e = objectMapper.readValue(json, CacheEntry.class);
                    if (e.getSourceDocIds() != null && e.getSourceDocIds().contains(docId)) {
                        redis.delete(key);
                        deleted++;
                    }
                } catch (Exception ignored) { }
            }
        }
        if (deleted > 0) {
            log.info("语义缓存失效: {} 条因 docId={}", deleted, docId);
        }
    }
}
