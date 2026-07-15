package com.zhishu.vector;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 Milvus 的向量存储服务。
 * 连接由 {@link MilvusClientManager} 管理，Milvus 不可用时自动降级（返回空结果）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorStoreService {

    private final MilvusClientManager milvusManager;
    private final String collectionName;

    private static final List<String> OUTPUT_FIELDS = List.of(
            "doc_id", "chunk_id", "seq", "file_name", "content", "project_id", "source");

    public void save(VectorRecord record) {
        var client = milvusManager.getClient();
        if (client == null) { log.warn("Milvus 不可用，跳过向量存储"); return; }
        JsonObject row = toJson(record);
        client.insert(InsertReq.builder().collectionName(collectionName).data(List.of(row)).build());
    }

    public void saveWithKey(String keyPrefix, VectorRecord record) {
        save(record);
    }

    public List<ScoredChunk> search(float[] queryVector, int topK) {
        return searchByPrefix("", queryVector, topK);
    }

    public List<ScoredChunk> searchByPrefix(String keyPrefix, float[] queryVector, int topK) {
        var client = milvusManager.getClient();
        if (client == null) { log.warn("Milvus 不可用，搜索返回空"); return List.of(); }
        String filter = buildFilterFromPrefix(keyPrefix);
        return searchWithFilter(client, queryVector, topK, filter);
    }

    public List<ScoredChunk> searchByProject(Long projectId, String source, float[] queryVector, int topK) {
        var client = milvusManager.getClient();
        if (client == null) return List.of();
        String filter = "project_id == " + projectId + " and source == \"" + source + "\"";
        return searchWithFilter(client, queryVector, topK, filter);
    }

    private List<ScoredChunk> searchWithFilter(io.milvus.v2.client.MilvusClientV2 client, float[] queryVector, int topK, String filter) {
        List<ScoredChunk> results = new ArrayList<>();
        try {
            SearchReq req = SearchReq.builder()
                    .collectionName(collectionName)
                    .annsField("vector")
                    .data(List.of(new FloatVec(queryVector)))
                    .topK(topK)
                    .filter(filter)
                    .outputFields(OUTPUT_FIELDS)
                    .metricType(IndexParam.MetricType.COSINE)
                    .build();

            SearchResp resp = client.search(req);
            if (resp.getSearchResults() != null) {
                for (var docResults : resp.getSearchResults()) {
                    for (var sr : docResults) {
                        var chunk = toScoredChunk(sr.getEntity(), sr.getScore());
                        if (chunk != null) results.add(chunk);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Milvus 搜索失败", e);
        }
        return results;
    }

    public void deleteByDoc(Long docId) {
        var client = milvusManager.getClient();
        if (client == null) return;
        client.delete(DeleteReq.builder()
                .collectionName(collectionName)
                .filter("doc_id == " + docId)
                .build());
    }

    private String buildFilterFromPrefix(String keyPrefix) {
        if (keyPrefix == null || keyPrefix.isBlank()) return "";
        String p = keyPrefix.replace("*", "");
        String[] parts = p.split(":");
        if (parts.length >= 3 && "vec".equals(parts[0])) {
            String pid = parts[1];
            if (parts.length >= 4) {
                return "project_id == " + pid + " and source == \"" + parts[2] + "\"";
            }
            return "project_id == " + pid;
        }
        return "";
    }

    private JsonObject toJson(VectorRecord record) {
        JsonObject json = new JsonObject();
        json.addProperty("project_id", record.getDocId() != null ? record.getDocId() : 0L);
        json.addProperty("source", "doc");
        json.addProperty("doc_id", record.getDocId() != null ? record.getDocId() : 0L);
        json.addProperty("chunk_id", record.getChunkId() != null ? record.getChunkId() : 0L);
        json.addProperty("seq", record.getSeq() != null ? record.getSeq() : 0);
        json.addProperty("file_name", record.getFileName() != null ? record.getFileName() : "");
        json.addProperty("content", record.getContent() != null ? record.getContent() : "");
        float[] vec = record.getVector();
        if (vec != null) {
            JsonArray arr = new JsonArray();
            for (float v : vec) arr.add(v);
            json.add("vector", arr);
        }
        return json;
    }

    private ScoredChunk toScoredChunk(Map<String, Object> entity, Float score) {
        if (entity == null) return null;
        Object fileName = entity.get("file_name");
        Object content = entity.get("content");
        Object docId = entity.get("doc_id");
        Object chunkId = entity.get("chunk_id");
        Object seq = entity.get("seq");
        if (content == null) return null;
        return new ScoredChunk(
                chunkId instanceof Number ? ((Number) chunkId).longValue() : 0L,
                docId instanceof Number ? ((Number) docId).longValue() : 0L,
                seq instanceof Number ? ((Number) seq).intValue() : 0,
                fileName != null ? fileName.toString() : "",
                content.toString(),
                score != null ? score.doubleValue() : 0.0);
    }

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
