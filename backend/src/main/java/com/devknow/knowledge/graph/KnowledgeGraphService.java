package com.devknow.knowledge.graph;

import com.devknow.knowledge.KnowledgeDocument;
import com.devknow.knowledge.KnowledgeDocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.input.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Neo4j Embedded 知识图谱服务。
 *
 * <p>管理文档节点和关系，支持 Cypher 多跳遍历。
 * 所有写操作都在事务中执行，读操作使用只读事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeGraphService {

    private final DatabaseManagementService managementService;
    private final KnowledgeDocumentRepository documentRepository;
    private final ChatLanguageModel chatModel;
    private final ObjectMapper objectMapper;

    private static final String DEFAULT_DB = "neo4j";
    private static final Label DOC_LABEL = Label.label("Document");

    // ==================== 节点操作 ====================

    /**
     * 创建或更新文档节点。
     * 使用 MERGE 语义，docId 为唯一标识。
     */
    public void createOrUpdateNode(Long docId, String title, int level, String tags) {
        executeInTransaction(tx -> {
            tx.execute("MERGE (d:Document {docId: $docId}) " +
                            "SET d.title = $title, d.level = $level, " +
                            "d.tags = $tags, d.updatedAt = timestamp()",
                    Map.of("docId", docId, "title", title != null ? title : "",
                            "level", level, "tags", tags != null ? tags : ""));
            return null;
        });
    }

    /**
     * 删除文档节点及其所有关系。
     */
    public void deleteNode(Long docId) {
        executeInTransaction(tx -> {
            tx.execute("MATCH (d:Document {docId: $docId}) DETACH DELETE d",
                    Map.of("docId", docId));
            return null;
        });
    }

    // ==================== 关系操作 ====================

    /**
     * 在两个文档之间创建关系。
     */
    public void createRelation(Long sourceDocId, Long targetDocId, DocRelationType type) {
        executeInTransaction(tx -> {
            tx.execute("MATCH (a:Document {docId: $sourceId}), (b:Document {docId: $targetId}) " +
                            "MERGE (a)-[r:" + type.name() + "]->(b) " +
                            "SET r.createdAt = timestamp()",
                    Map.of("sourceId", sourceDocId, "targetId", targetDocId));
            return null;
        });
    }

    /**
     * 删除两个文档之间的指定关系。
     */
    public void deleteRelation(Long sourceDocId, Long targetDocId, DocRelationType type) {
        executeInTransaction(tx -> {
            tx.execute("MATCH (a:Document {docId: $sourceId})-[r:" + type.name() + "]->(b:Document {docId: $targetId}) " +
                            "DELETE r",
                    Map.of("sourceId", sourceDocId, "targetId", targetDocId));
            return null;
        });
    }

    // ==================== 图谱遍历 ====================

    /**
     * 查找与指定文档关联的文档（N 跳内）。
     *
     * @param docId   起始文档 ID
     * @param maxHops 最大跳数（1~5）
     * @return 关联文档列表，按跳数升序
     */
    public List<GraphRelationResult> findRelated(Long docId, int maxHops) {
        return executeInTransaction(tx -> {
            int hops = Math.min(Math.max(maxHops, 1), 5);
            Result result = tx.execute(
                    "MATCH (d:Document {docId: $docId})-[*1.." + hops + "]-(related) " +
                            "WHERE related.docId <> $docId " +
                            "RETURN distinct related.docId AS docId, " +
                            "related.title AS title, related.level AS level, " +
                            "reduce(s = '', r IN relationships(p) | s + type(r) + ' ') AS relTypes, " +
                            "CASE WHEN related.level < d.level THEN 'UP' " +
                            "     WHEN related.level > d.level THEN 'DOWN' " +
                            "     ELSE 'SAME' END AS direction, " +
                            "length(p) AS hops " +
                            "ORDER BY hops, related.level",
                    Map.of("docId", docId));
            return mapResults(result);
        });
    }

    /**
     * 查找两个文档之间的最短路径。
     */
    public List<GraphRelationResult> findShortestPath(Long sourceId, Long targetId) {
        return executeInTransaction(tx -> {
            Result result = tx.execute(
                    "MATCH p = shortestPath(" +
                            "  (a:Document {docId: $sourceId})-[*..10]-(b:Document {docId: $targetId})" +
                            ") " +
                            "UNWIND nodes(p) AS n " +
                            "WHERE n.docId <> $sourceId " +
                            "RETURN n.docId AS docId, n.title AS title, " +
                            "n.level AS level, length(p) AS hops",
                    Map.of("sourceId", sourceId, "targetId", targetId));
            return mapResults(result);
        });
    }

    /**
     * 查找指定层级之上的决策链路。
     * 从当前文档向上追溯原则和架构决策。
     */
    public List<GraphRelationResult> traceUpstream(Long docId) {
        return executeInTransaction(tx -> {
            Result result = tx.execute(
                    "MATCH path = (d:Document {docId: $docId})-[:REFERENCES|DEPENDS_ON*]->(ancestor) " +
                            "WHERE ancestor.level < d.level " +
                            "RETURN ancestor.docId AS docId, ancestor.title AS title, " +
                            "ancestor.level AS level, length(path) AS hops " +
                            "ORDER BY ancestor.level, hops",
                    Map.of("docId", docId));
            return mapResults(result);
        });
    }

    /**
     * 查找受当前文档影响的实现层文档。
     * 从当前文档向下寻找依赖方。
     */
    public List<GraphRelationResult> traceDownstream(Long docId) {
        return executeInTransaction(tx -> {
            Result result = tx.execute(
                    "MATCH path = (d:Document {docId: $docId})<-[:DEPENDS_ON|EXTENDS*]-(affected) " +
                            "WHERE affected.level > d.level " +
                            "RETURN affected.docId AS docId, affected.title AS title, " +
                            "affected.level AS level, length(path) AS hops " +
                            "ORDER BY affected.level, hops",
                    Map.of("docId", docId));
            return mapResults(result);
        });
    }

    // ==================== 统计 ====================

    /**
     * 图谱统计信息。
     */
    public Map<String, Object> getStats() {
        return executeInTransaction(tx -> {
            Map<String, Object> stats = new LinkedHashMap<>();

            // 节点总数
            Result nodeCount = tx.execute("MATCH (d:Document) RETURN count(d) AS total");
            stats.put("totalNodes", nodeCount.hasNext() ? nodeCount.next().get("total") : 0L);

            // 各层级节点数
            Result levelDist = tx.execute(
                    "MATCH (d:Document) WHERE d.level > 0 " +
                            "RETURN d.level AS level, count(d) AS count ORDER BY level");
            Map<Integer, Long> levelMap = new LinkedHashMap<>();
            while (levelDist.hasNext()) {
                var row = levelDist.next();
                levelMap.put(((Number) row.get("level")).intValue(),
                        ((Number) row.get("count")).longValue());
            }
            stats.put("nodesByLevel", levelMap);

            // 关系总数
            Result relCount = tx.execute("MATCH ()-[r]->() RETURN count(r) AS total");
            stats.put("totalRelations", relCount.hasNext() ? relCount.next().get("total") : 0L);

            // 关系类型分布
            Result relDist = tx.execute(
                    "MATCH ()-[r]->() RETURN type(r) AS type, count(r) AS count");
            Map<String, Long> relMap = new LinkedHashMap<>();
            while (relDist.hasNext()) {
                var row = relDist.next();
                relMap.put((String) row.get("type"), ((Number) row.get("count")).longValue());
            }
            stats.put("relationsByType", relMap);

            return stats;
        });
    }

    // ==================== LLM 自动建关系 ====================

    /**
     * 为新上传的文档自动分析并建立关系。
     * 在 DocumentParseListener 中上传完成后调用。
     *
     * @param docId      新文档 ID
     * @param title      文档标题
     * @param summary    文档摘要（前 500 字）
     */
    public void autoBuildRelations(Long docId, String title, String summary) {
        List<KnowledgeDocument> existingDocs = documentRepository.findAll();
        if (existingDocs.isEmpty()) return;

        // 构建已有文档列表摘要
        String existingList = existingDocs.stream()
                .limit(30)
                .map(d -> String.format("  ID=%d, 标题=%s", d.getId(), d.getFileName()))
                .collect(Collectors.joining("\n"));

        String prompt = PromptTemplate.from("""
                你是一个知识库分析器。分析以下文档摘要，判断它与知识库中哪些已有文档存在关系。

                新文档：
                  标题: {{title}}
                  摘要: {{summary}}

                已有文档列表：
                {{existingDocs}}

                输出格式：{"relations": [{"targetDocId": int, "type": "REFERENCES|DEPENDS_ON|EXTENDS|SEQUEL_TO", "reason": "string"}]}
                如果没有关联关系，返回空数组：{"relations": []}
                """)
                .apply(Map.of(
                        "title", title,
                        "summary", summary != null && summary.length() > 500 ? summary.substring(0, 500) : (summary != null ? summary : ""),
                        "existingDocs", existingList))
                .text();

        try {
            String response = chatModel.chat(ChatRequest.builder()
                    .messages(UserMessage.from(prompt)).build()).aiMessage().text();
            List<Map<String, Object>> relations = parseRelationResponse(response);

            for (Map<String, Object> rel : relations) {
                int targetId = ((Number) rel.get("targetDocId")).intValue();
                String typeStr = (String) rel.get("type");
                try {
                    DocRelationType type = DocRelationType.valueOf(typeStr);
                    createRelation(docId, (long) targetId, type);
                    log.info("图谱边自动建立: {} --[{}]--> {}", docId, type, targetId);
                } catch (IllegalArgumentException e) {
                    log.warn("未知关系类型: {}, 跳过", typeStr);
                }
            }
        } catch (Exception e) {
            log.warn("自动建关系失败（docId={}）: {}", docId, e.getMessage());
        }
    }

    /**
     * 全量自动构建图谱关系（遍历所有无关系的文档）。
     * 通过 REST API 手动触发。
     */
    public void buildAllRelations() {
        List<KnowledgeDocument> docs = documentRepository.findAll();
        for (int i = 0; i < docs.size(); i++) {
            KnowledgeDocument doc = docs.get(i);
            autoBuildRelations(doc.getId(), doc.getFileName(), "");
        }
        log.info("全量图谱关系构建完成，处理 {} 篇文档", docs.size());
    }

    // ==================== 内部方法 ====================

    private GraphDatabaseService getGraphDb() {
        return managementService.database(DEFAULT_DB);
    }

    private <T> T executeInTransaction(TransactionCallback<T> callback) {
        try (Transaction tx = getGraphDb().beginTx()) {
            T result = callback.execute(tx);
            tx.commit();
            return result;
        }
    }

    private List<GraphRelationResult> mapResults(Result result) {
        List<GraphRelationResult> list = new ArrayList<>();
        while (result.hasNext()) {
            Map<String, Object> row = result.next();
            long id = ((Number) row.getOrDefault("docId", 0L)).longValue();
            String title = (String) row.getOrDefault("title", "");
            int level = ((Number) row.getOrDefault("level", 0)).intValue();
            String relType = (String) row.getOrDefault("relTypes", "");
            int hops = ((Number) row.getOrDefault("hops", 0)).intValue();
            list.add(new GraphRelationResult(0L, id, title, level, relType, hops));
        }
        return list;
    }

    // ==================== 批量查询 ====================

    /**
     * 批量查询多个文档的关联文档（一次 Neo4j 查询，替代逐 docId 循环）。
     *
     * @param docIds  起始文档 ID 列表
     * @param maxHops 最大跳数（1~5）
     * @return 关联文档列表（包含 sourceDocId 标记来源）
     */
    public List<GraphRelationResult> findRelatedBatch(List<Long> docIds, int maxHops) {
        if (docIds == null || docIds.isEmpty()) return List.of();
        return executeInTransaction(tx -> {
            int hops = Math.min(Math.max(maxHops, 1), 5);
            // 用 UNWIND 处理批量输入
            Result result = tx.execute(
                    "UNWIND $docIds AS sourceDocId " +
                    "MATCH (d:Document {docId: sourceDocId})-[*1.." + hops + "]-(related) " +
                    "WHERE related.docId <> sourceDocId " +
                    "RETURN distinct sourceDocId AS sourceDocId, " +
                    "related.docId AS docId, " +
                    "related.title AS title, related.level AS level, " +
                    "reduce(s = '', r IN relationships(p) | s + type(r) + ' ') AS relTypes, " +
                    "length(p) AS hops " +
                    "ORDER BY hops, related.level",
                    Map.of("docIds", docIds));
            List<GraphRelationResult> list = new ArrayList<>();
            while (result.hasNext()) {
                Map<String, Object> row = result.next();
                long sourceDocId = ((Number) row.getOrDefault("sourceDocId", 0L)).longValue();
                long id = ((Number) row.getOrDefault("docId", 0L)).longValue();
                String title = (String) row.getOrDefault("title", "");
                int level = ((Number) row.getOrDefault("level", 0)).intValue();
                String relType = (String) row.getOrDefault("relTypes", "");
                int hopsVal = ((Number) row.getOrDefault("hops", 0)).intValue();
                list.add(new GraphRelationResult(sourceDocId, id, title, level, relType, hopsVal));
            }
            return list;
        });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseRelationResponse(String response) {
        try {
            String json = response;
            if (json.contains("```json")) {
                json = json.substring(json.indexOf("```json") + 7);
                json = json.substring(0, json.indexOf("```"));
            } else if (json.contains("```")) {
                json = json.substring(json.indexOf("```") + 3);
                json = json.substring(0, json.indexOf("```"));
            }
            json = json.trim();

            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            Object rels = map.get("relations");
            if (rels instanceof List) {
                return (List<Map<String, Object>>) rels;
            }
        } catch (Exception e) {
            log.warn("关系响应解析失败: {}", e.getMessage());
        }
        return List.of();
    }

    // ==================== 内部类型 ====================

    @FunctionalInterface
    private interface TransactionCallback<T> {
        T execute(Transaction tx);
    }

    /** 文档节点批量导入用 */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class DocumentNode {
        private Long docId;
        private String title;
        private int level;
        private String tags;
    }

    /** 关系批量导入用 */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class DocRelation {
        private Long sourceDocId;
        private Long targetDocId;
        private DocRelationType type;
    }
}
