package com.devknow.knowledge.graph;

import com.devknow.knowledge.KnowledgeDocument;
import com.devknow.knowledge.KnowledgeDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 知识图谱管理 REST API。
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge/graph")
@RequiredArgsConstructor
public class GraphController {

    private final KnowledgeGraphService graphService;
    private final KnowledgeDocumentRepository documentRepository;

    /**
     * 手动添加文档关系。
     */
    @PostMapping("/relation")
    public ResponseEntity<?> addRelation(@RequestBody Map<String, Object> body) {
        try {
            Long sourceId = ((Number) body.get("sourceDocId")).longValue();
            Long targetId = ((Number) body.get("targetDocId")).longValue();
            DocRelationType type = DocRelationType.valueOf((String) body.get("type"));
            graphService.createRelation(sourceId, targetId, type);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 删除文档关系。
     */
    @DeleteMapping("/relation")
    public ResponseEntity<?> deleteRelation(@RequestParam Long sourceId,
                                            @RequestParam Long targetId,
                                            @RequestParam String type) {
        try {
            graphService.deleteRelation(sourceId, targetId, DocRelationType.valueOf(type));
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 查看文档的关联图谱。
     */
    @GetMapping("/{docId}")
    public ResponseEntity<?> getGraph(@PathVariable Long docId,
                                      @RequestParam(defaultValue = "3") int hops) {
        try {
            List<GraphRelationResult> related = graphService.findRelated(docId, hops);
            List<GraphRelationResult> upstream = graphService.traceUpstream(docId);
            List<GraphRelationResult> downstream = graphService.traceDownstream(docId);
            return ResponseEntity.ok(Map.of(
                    "docId", docId,
                    "related", related,
                    "upstream", upstream,
                    "downstream", downstream,
                    "stats", graphService.getStats()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 两点间最短路径。
     */
    @GetMapping("/{docId}/path")
    public ResponseEntity<?> findPath(@PathVariable Long docId,
                                      @RequestParam Long target) {
        try {
            List<GraphRelationResult> path = graphService.findShortestPath(docId, target);
            return ResponseEntity.ok(Map.of("path", path));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 触发全量自动关系构建。
     */
    @PostMapping("/auto-build")
    public ResponseEntity<?> autoBuild() {
        try {
            graphService.buildAllRelations();
            return ResponseEntity.ok(Map.of("status", "started"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 同步文档到 Neo4j（将 KnowledgeDocument 全部同步为图谱节点）。
     */
    @PostMapping("/sync-nodes")
    public ResponseEntity<?> syncNodes() {
        try {
            List<KnowledgeDocument> docs = documentRepository.findAll();
            for (KnowledgeDocument doc : docs) {
                graphService.createOrUpdateNode(doc.getId(), doc.getFileName(), 0, "");
            }
            log.info("图谱节点同步完成: {} 篇文档", docs.size());
            return ResponseEntity.ok(Map.of("status", "ok", "count", docs.size()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 图谱统计。
     */
    @GetMapping("/stats")
    public ResponseEntity<?> stats() {
        try {
            Map<String, Object> stats = graphService.getStats();
        return ResponseEntity.ok(stats);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
