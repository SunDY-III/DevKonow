package com.devknow.vector;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.PointId;
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
 * 基于 Qdrant 的向量存储服务。
 * 连接由 {@link QdrantClientManager} 管理，Qdrant 不可用时自动降级。
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

    // ==================== 检索 ====================

    public List<ScoredChunk> search(float[] queryVector, int topK) {
        return searchWithFilter(queryVector, topK, null);
    }

    public List<ScoredChunk> searchByPrefix(String keyPrefix, float[] queryVector, int topK) {
        Filter filter = buildFilterFromPrefix(keyPrefix);
        return searchWithFilter(queryVector, topK, filter);
    }

    public List<ScoredChunk> searchByLevels(float[] queryVector, int topK, int[] levels) {
        if (levels == null || levels.length == 0) return search(queryVector, topK);
        if (levels.length == 1) {
            Filter filter = Filter.newBuilder()
                    .addMust(match("level", levels[0]))
                    .build();
            return searchWithFilter(queryVector, topK, filter);
        }
        var conditions = new ArrayList<io.qdrant.client.grpc.Points.Condition>();
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

    // ==================== 向量回查（MMR 用） ====================

    /**
     * 按 chunkId 批量回查 Qdrant 中的原始向量。
     * key = "docId:chunkId"，用于 MmrSelector 的 vectorResolver。
     */
    public Map<String, float[]> retrieveVectors(List<Long> chunkIds) {
        QdrantClient client = qdrantManager.getClient();
        if (client == null || chunkIds == null || chunkIds.isEmpty()) return Map.of();

        try {
            List<PointId> pointIds = chunkIds.stream()
                    .map(id -> PointId.newBuilder().setNum(id).build())
                    .toList();

            List<io.qdrant.client.grpc.Points.RetrievedPoint> points = client.retrieveAsync(collectionName, pointIds, io.qdrant.client.grpc.Points.WithPayloadSelector.newBuilder().setEnable(false).build(), io.qdrant.client.grpc.Points.WithVectorsSelector.newBuilder().setEnable(true).build(), null)
                    .get(5, TimeUnit.SECONDS);

            Map<String, float[]> result = new java.util.HashMap<>();
            for (io.qdrant.client.grpc.Points.RetrievedPoint p : points) {
                long cid = p.getId().getNum();
                var vec = p.getVectors().getVector().getDataList();
                float[] arr = new float[vec.size()];
                for (int i = 0; i < vec.size(); i++) arr[i] = vec.get(i);
                result.put(cid + ":" + cid, arr);
            }
            return result;
        } catch (Exception e) {
            log.warn("Qdrant 向量回查失败", e);
            return Map.of();
        }
    }

    // ==================== 内部实现 ====================

    private List<ScoredChunk> searchWithFilter(float[] queryVector, int topK, Filter filter) {
        QdrantClient client = qdrantManager.getClient();
        if (client == null) { log.warn("Qdrant 不可用，搜索返回空"); return List.of(); }

        List<Float> vectorList = new ArrayList<>(queryVector.length);
        for (float v : queryVector) vectorList.add(v);

        try {
            SearchPoints.Builder builder = SearchPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .addAllVector(vectorList)
                    .setLimit(Math.max(topK, 1))
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build());

            if (filter != null) builder.setFilter(filter);

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

    private PointStruct toPoint(VectorRecord record) {
        float[] vec = record.getVector();
        Long chunkId = record.getChunkId() != null ? record.getChunkId() : 0L;
        Long docId = record.getDocId() != null ? record.getDocId() : 0L;

        return PointStruct.newBuilder()
                .setId(id(chunkId))
                .setVectors(vectors(vec))
                .putAllPayload(Map.of(
                        "project_id", value(docId),
                        "source", value("doc"),
                        "doc_id", value(docId),
                        "chunk_id", value(chunkId),
                        "seq", value(record.getSeq() != null ? record.getSeq() : 0),
                        "file_name", value(record.getFileName() != null ? record.getFileName() : ""),
                        "content", value(record.getContent() != null ? record.getContent() : ""),
                        "level", value(record.getLevel() != null ? record.getLevel() : 0)
                ))
                .build();
    }

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
                fileName, content, score, "");
    }

    private long extractLong(Map<String, JsonWithInt.Value> payload, String key) {
        if (payload == null) return 0L;
        JsonWithInt.Value v = payload.get(key);
        if (v == null) return 0L;
        return v.getIntegerValue();
    }

    private String extractString(Map<String, JsonWithInt.Value> payload, String key) {
        if (payload == null) return "";
        JsonWithInt.Value v = payload.get(key);
        if (v == null) return "";
        return v.getStringValue();
    }

    // ==================== 旧接口 keyPrefix 兼容 ====================

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
