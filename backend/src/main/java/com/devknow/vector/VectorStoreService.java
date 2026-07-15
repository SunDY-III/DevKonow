package com.devknow.vector;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;
import static io.qdrant.client.ConditionFactory.match;
import static io.qdrant.client.ConditionFactory.matchKeyword;

/**
 * 基于 Qdrant 的向量存储服务（轻量级 Rust 向量数据库，替代 Milvus）。
 * 连接由 {@link QdrantClientManager} 管理，Qdrant 不可用时自动降级（返回空结果）。
 *
 * <p>Qdrant 为 schemaless 设计，无需预定义字段 schema，payload 随点写入。
 * 默认使用 COSINE 距离 + HNSW 索引。
 */
@Slf4j
@Service
public class VectorStoreService {

    private final QdrantClientManager qdrantManager;
    private final String collectionName;

    public VectorStoreService(QdrantClientManager qdrantManager,
                              @Value("${qdrant.collection:devknow_vectors}") String collectionName) {
        this.qdrantManager = qdrantManager;
        this.collectionName = collectionName;
    }

    // ==================== 写入 ====================

    public void save(VectorRecord record) {
        QdrantClient client = qdrantManager.getClient();
        if (client == null) { log.warn("Qdrant 不可用，跳过向量存储"); return; }

        try {
            client.upsertAsync(collectionName, List.of(toPoint(record)))
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Qdrant 写入失败", e);
        }
    }

    /** saveWithKey 在 Qdrant 中与 save 等价（无需 key 前缀隔离）。 */
    public void saveWithKey(String keyPrefix, VectorRecord record) {
        save(record);
    }

    // ==================== 检索 ====================

    public List<ScoredChunk> search(float[] queryVector, int topK) {
        return searchWithFilter(queryVector, topK, null);
    }

    public List<ScoredChunk> searchByPrefix(String keyPrefix, float[] queryVector, int topK) {
        Filter filter = buildFilterFromPrefix(keyPrefix);
        return searchWithFilter(queryVector, topK, filter);
    }

    public List<ScoredChunk> searchByProject(Long projectId, String source, float[] queryVector, int topK) {
        Filter filter = Filter.newBuilder()
                .addMust(match("project_id", projectId))
                .addMust(matchKeyword("source", source))
                .build();
        return searchWithFilter(queryVector, topK, filter);
    }

    /**
     * 按层级范围搜索向量库。
     *
     * @param queryVector 查询向量
     * @param topK        返回条数
     * @param levels      层级范围（如 [2] 或 [1,2,3]）
     * @return 匹配的文档块列表
     */
    public List<ScoredChunk> searchByLevels(float[] queryVector, int topK, int[] levels) {
        if (levels == null || levels.length == 0) return search(queryVector, topK);
        if (levels.length == 1) {
            Filter filter = Filter.newBuilder()
                    .addMust(match("level", levels[0]))
                    .build();
            return searchWithFilter(queryVector, topK, filter);
        }
        // 多个层级用 OR 条件
        var conditions = new java.util.ArrayList<io.qdrant.client.grpc.Points.Condition>();
        for (int level : levels) {
            conditions.add(match("level", level));
        }
        Filter filter = Filter.newBuilder()
                .addMust(io.qdrant.client.grpc.Points.Condition.newBuilder()
                        .setFilter(Filter.newBuilder().addAllShould(conditions).build())
                        .build())
                .build();
        return searchWithFilter(queryVector, topK, filter);
    }

    // ==================== 删除 ====================

    public void deleteByDoc(Long docId) {
        QdrantClient client = qdrantManager.getClient();
        if (client == null) return;
        try {
            Filter filter = Filter.newBuilder()
                    .addMust(match("doc_id", docId))
                    .build();
            client.deleteAsync(collectionName, filter)
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Qdrant 删除失败（docId={}）", docId, e);
        }
    }

    // ==================== 内部实现 ====================

    private List<ScoredChunk> searchWithFilter(float[] queryVector, int topK, Filter filter) {
        QdrantClient client = qdrantManager.getClient();
        if (client == null) { log.warn("Qdrant 不可用，搜索返回空"); return List.of(); }

        // float[] → List<Float>
        List<Float> vectorList = new ArrayList<>(queryVector.length);
        for (float v : queryVector) vectorList.add(v);

        try {
            SearchPoints.Builder builder = SearchPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .addAllVector(vectorList)
                    .setLimit(Math.max(topK, 1))
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build());

            if (filter != null) {
                builder.setFilter(filter);
            }

            List<ScoredPoint> results = client.searchAsync(builder.build())
                    .get(5, TimeUnit.SECONDS);

            return results.stream()
                    .map(this::toScoredChunk)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Qdrant 搜索失败", e);
            return List.of();
        }
    }

    /** 构建 Qdrant PointStruct（文档/代码向量点）。 */
    private PointStruct toPoint(VectorRecord record) {
        float[] vec = record.getVector();
        Long chunkId = record.getChunkId() != null ? record.getChunkId() : 0L;
        Long docId = record.getDocId() != null ? record.getDocId() : 0L;

        Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload = new java.util.LinkedHashMap<>();
        payload.put("project_id", value(docId));
        payload.put("source", value("doc"));
        payload.put("doc_id", value(docId));
        payload.put("chunk_id", value(chunkId));
        payload.put("seq", value(record.getSeq() != null ? record.getSeq() : 0));
        payload.put("file_name", value(record.getFileName() != null ? record.getFileName() : ""));
        payload.put("content", value(record.getContent() != null ? record.getContent() : ""));
        payload.put("level", value(record.getLevel() != null ? record.getLevel() : 0));

        return PointStruct.newBuilder()
                .setId(id(chunkId))
                .setVectors(vectors(vec))
                .putAllPayload(payload)
                .build();
    }

    /** 将 Qdrant ScoredPoint 转为统一 ScoredChunk。 */
    private ScoredChunk toScoredChunk(ScoredPoint point) {
        if (point == null) return null;

        double score = point.getScore();
        Map<String, JsonWithInt.Value> payload = point.getPayloadMap();

        long docId = extractLong(payload, "doc_id");
        long chunkId = extractLong(payload, "chunk_id");
        int seq = (int) extractLong(payload, "seq");
        String fileName = extractString(payload, "file_name");
        String content = extractString(payload, "content");

        if (content.isEmpty() && chunkId == 0L) return null;

        return new ScoredChunk(
                chunkId, docId, seq,
                fileName, content, score);
    }

    /** 从 payload 中安全提取 long 值。 */
    private long extractLong(Map<String, JsonWithInt.Value> payload, String key) {
        if (payload == null) return 0L;
        JsonWithInt.Value v = payload.get(key);
        if (v == null) return 0L;
        return v.getIntegerValue();
    }

    /** 从 payload 中安全提取 String 值。 */
    private String extractString(Map<String, JsonWithInt.Value> payload, String key) {
        if (payload == null) return "";
        JsonWithInt.Value v = payload.get(key);
        if (v == null) return "";
        return v.getStringValue();
    }

    // ==================== 旧接口 keyPrefix 兼容 ====================

    /**
     * 将旧版 keyPrefix（如 "vec:0:code:*"）转为 Qdrant Filter。
     * 格式：vec:{projectId}:{source}:* → project_id == {projectId} AND source == {source}
     */
    private Filter buildFilterFromPrefix(String keyPrefix) {
        if (keyPrefix == null || keyPrefix.isBlank()) return null;
        String p = keyPrefix.replace("*", "");
        String[] parts = p.split(":");
        if (parts.length >= 3 && "vec".equals(parts[0])) {
            long pid;
            try {
                pid = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                return null;
            }
            Filter.Builder builder = Filter.newBuilder();
            builder.addMust(match("project_id", pid));
            if (parts.length >= 4 && !parts[2].isEmpty()) {
                builder.addMust(matchKeyword("source", parts[2]));
            }
            return builder.build();
        }
        return null;
    }

    // ==================== 静态工具 ====================

    /** 余弦相似度。SemanticCacheService 和 MmrSelector 依赖此静态方法。 */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
