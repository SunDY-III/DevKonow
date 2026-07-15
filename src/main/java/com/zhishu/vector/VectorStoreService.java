package com.zhishu.vector;

import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
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
 *
 * <p>替换了原有的 Redis SCAN 穷举方案，使用 Milvus IVF_FLAT 索引
 * 实现近似最近邻搜索（ANN），支持百万级向量规模。
 *
 * <p>集合结构（自动初始化）：
 * <ul>
 *   <li>id (Int64, PK, autoID)</li>
 *   <li>project_id (Int64) — 项目隔离</li>
 *   <li>source (VarChar) — "doc" 或 "code"</li>
 *   <li>doc_id / chunk_id / seq — 引用信息</li>
 *   <li>file_name / content — 元数据</li>
 *   <li>vector (FloatVector, 1536d) — 向量</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorStoreService {

    private final MilvusClientV2 milvusClient;
    private final String collectionName;

    /** 搜索时的默认输出字段 */
    private static final List<String> OUTPUT_FIELDS = List.of(
            "doc_id", "chunk_id", "seq", "file_name", "content", "project_id", "source");

    /**
     * 保存向量记录到 Milvus。
     */
    public void save(VectorRecord record) {
        JsonObject row = toJson(record);
        InsertReq insertReq = InsertReq.builder()
                .collectionName(collectionName)
                .data(List.of(row))
                .build();
        milvusClient.insert(insertReq);
    }

    /**
     * 使用自定义前缀保存（兼容 Redis 时代的接口）。
     * project_id + source 已经能实现隔离，prefix 参数不再需要。
     */
    public void saveWithKey(String keyPrefix, VectorRecord record) {
        save(record);
    }

    /**
     * 余弦相似度 TopK 搜索。
     */
    public List<ScoredChunk> search(float[] queryVector, int topK) {
        return searchByPrefix("", queryVector, topK);
    }

    /**
     * 按项目 + 源搜索（替换 Redis 时代的 keyPrefix）。
     * <p>
     * 过滤条件示例：
     * <ul>
     *   <li>搜文档：project_id == 0 and source == "doc"</li>
     *   <li>搜代码：project_id == 1 and source == "code"</li>
     *   <li>搜全部：不传 filter</li>
     * </ul>
     *
     * @param keyPrefix  不再使用，兼容接口签名
     * @param queryVector 查询向量
     * @param topK       返回条数
     * @return 相似度降序的 ScoredChunk 列表
     */
    public List<ScoredChunk> searchByPrefix(String keyPrefix, float[] queryVector, int topK) {
        // 从 keyPrefix 中解析 project_id 和 source（兼容旧调用方）
        String filter = buildFilterFromPrefix(keyPrefix);
        return searchWithFilter(queryVector, topK, filter);
    }

    /**
     * 按项目 ID 和源类型搜索。
     *
     * @param projectId  项目 ID（0 = 共享文档）
     * @param source     "doc" 或 "code"
     * @param queryVector 查询向量
     * @param topK       返回条数
     */
    public List<ScoredChunk> searchByProject(Long projectId, String source,
                                               float[] queryVector, int topK) {
        String filter = "project_id == " + projectId + " and source == \"" + source + "\"";
        return searchWithFilter(queryVector, topK, filter);
    }

    /**
     * 实际执行 Milvus 搜索。
     */
    private List<ScoredChunk> searchWithFilter(float[] queryVector, int topK, String filter) {
        List<ScoredChunk> results = new ArrayList<>();

        try {
            SearchReq searchReq = SearchReq.builder()
                    .collectionName(collectionName)
                    .annsField("vector")
                    .data(List.of(new FloatVec(queryVector)))
                    .topK(topK)
                    .filter(filter)
                    .outputFields(OUTPUT_FIELDS)
                    .metricType(IndexParam.MetricType.COSINE)
                    .build();

            SearchResp searchResp = milvusClient.search(searchReq);

            if (searchResp.getSearchResults() != null) {
                for (var docResults : searchResp.getSearchResults()) {
                    for (var searchResult : docResults) {
                        try {
                            // searchResult.getEntity() → Map<String, Object>
                            // searchResult.getScore() → Float
                            Map<String, Object> entity = searchResult.getEntity();
                            Float score = searchResult.getScore();

                            ScoredChunk chunk = toScoredChunk(entity, score);
                            if (chunk != null) results.add(chunk);

                        } catch (Exception e) {
                            log.warn("Milvus 结果转换失败", e);
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.warn("Milvus 搜索失败", e);
        }

        return results;
    }

    /**
     * 按 docId 删除（文档更新/删除时清理旧向量）。
     */
    public void deleteByDoc(Long docId) {
        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(collectionName)
                .filter("doc_id == " + docId)
                .build();
        milvusClient.delete(deleteReq);
    }

    // ======================== 工具方法 ========================

    /**
     * 从旧的 keyPrefix 解析过滤条件。
     * vec:chunk:* → project_id == 0（兼容旧文档）
     * vec:1:code:* → project_id == 1 and source == "code"
     */
    private String buildFilterFromPrefix(String keyPrefix) {
        if (keyPrefix == null || keyPrefix.isBlank()) return "";
        // 去掉首尾的 "*"
        String p = keyPrefix.replace("*", "");
        String[] parts = p.split(":");

        if (parts.length >= 3 && "vec".equals(parts[0])) {
            String projectId = parts[1];
            if (parts.length >= 4) {
                String source = parts[2];
                return "project_id == " + projectId + " and source == \"" + source + "\"";
            }
            return "project_id == " + projectId;
        }
        return "";
    }

    /**
     * VectorRecord → JsonObject（Milvus 插入用）。
     */
    private JsonObject toJson(VectorRecord record) {
        JsonObject json = new JsonObject();
        json.addProperty("project_id", record.getDocId() != null ? record.getDocId() : 0L);
        json.addProperty("source", "doc");
        json.addProperty("doc_id", record.getDocId() != null ? record.getDocId() : 0L);
        json.addProperty("chunk_id", record.getChunkId() != null ? record.getChunkId() : 0L);
        json.addProperty("seq", record.getSeq() != null ? record.getSeq() : 0);
        json.addProperty("file_name", record.getFileName() != null ? record.getFileName() : "");
        json.addProperty("content", record.getContent() != null ? record.getContent() : "");

        // float[] → Json array
        float[] vec = record.getVector();
        if (vec != null) {
            var arr = new com.google.gson.JsonArray();
            for (float v : vec) {
                arr.add(v);
            }
            json.add("vector", arr);
        }

        return json;
    }

    /**
     * Milvus SearchResult → ScoredChunk。
     *
     * @param entity searchResult.getEntity() — 字段名 → 值的 Map
     * @param score  searchResult.getScore() — 余弦距离
     */
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
                score != null ? score.doubleValue() : 0.0
        );
    }

    /**
     * 余弦相似度（保留，供语义缓存等地方调用）。
     */
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
