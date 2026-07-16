package com.devknow.knowledge.graph;

import com.devknow.knowledge.DocumentChunk;
import com.devknow.knowledge.DocumentChunkRepository;
import com.devknow.vector.ScoredChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;


/**
 * 知识图谱上下文扩展器。
 *
 * <p>在 RAG 检索结果的基础上，通过 Neo4j 知识图谱发现关联文档，
 * 将相关文档的片段作为额外上下文追加到结果中。
 *
 * <p>扩展结果评分公式：
 * <pre>
 *   score = max(相关原始命中分) × weight(type) ^ hops
 *   weight(REFERENCES) = 0.8
 *   weight(DEPENDS_ON) = 0.7
 *   weight(EXTENDS)    = 0.6
 *   weight(SEQUEL_TO)  = 0.5
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphExpander {

    /** 关系类型 → 跳数衰减权重 */
    private static final Map<String, Double> RELATION_WEIGHT = Map.of(
            "REFERENCES",  0.8,
            "DEPENDS_ON",  0.7,
            "EXTENDS",     0.6,
            "SEQUEL_TO",   0.5
    );

    private static final double DEFAULT_WEIGHT = 0.6;

    private final KnowledgeGraphService graphService;
    private final DocumentChunkRepository chunkRepository;

    /**
     * 从检索命中结果出发，通过 Neo4j 扩展关联上下文。
     *
     * @param hits      RRF 融合后的 TopN 结果（非 null）
     * @param maxExtra  最多附加的关联文档数
     * @param maxHops   最大跳数
     * @return 扩展后的结果列表（原结果在前，关联推荐在后）
     */
    public List<ScoredChunk> expand(List<ScoredChunk> hits, int maxExtra, int maxHops) {
        if (hits == null || hits.isEmpty() || maxExtra <= 0) return hits;

        // 1. 建立原始命中的 docId → 最高分 映射
        Map<Long, Double> hitScoreMap = new HashMap<>();
        Set<Long> hitDocIds = new HashSet<>();
        for (ScoredChunk c : hits) {
            if (c.getDocId() != null && c.getDocId() > 0) {
                hitDocIds.add(c.getDocId());
                hitScoreMap.merge(c.getDocId(), c.getScore(), Math::max);
            }
        }
        if (hitDocIds.isEmpty()) return hits;

        // 2. 遍历命中文档，查 Neo4j 获取关联文档
        //    构建 relatedDocId → {baseScore, bestType, minHops}
        Map<Long, RelatedInfo> relatedMap = new HashMap<>();
        for (Long docId : hitDocIds) {
            try {
                List<GraphRelationResult> related = graphService.findRelated(docId, maxHops);
                for (GraphRelationResult r : related) {
                    if (hitDocIds.contains(r.getDocId())) continue;

                    double baseScore = hitScoreMap.getOrDefault(docId, 0.5);
                    String bestType = extractBestType(r.getRelationType());
                    int hops = r.getHops();

                    relatedMap.merge(r.getDocId(),
                            new RelatedInfo(baseScore, bestType, hops),
                            (old, cur) -> {
                                // 取更短的跳数（关联更紧）
                                if (cur.hops < old.hops) return cur;
                                // 同跳数取更高的 baseScore
                                if (cur.hops == old.hops && cur.baseScore > old.baseScore) return cur;
                                return old;
                            });
                }
            } catch (Exception e) {
                log.warn("图谱扩展查询失败（docId={}）: {}", docId, e.getMessage());
            }
        }
        if (relatedMap.isEmpty()) return hits;

        // 3. 评分 + 排序 + 限额
        List<ScoredChunk> extraChunks = relatedMap.entrySet().stream()
                .sorted(Map.Entry.<Long, RelatedInfo>comparingByValue(
                        Comparator.comparingDouble(ri -> -ri.calcScore())))
                .limit(maxExtra)
                .map(e -> fetchChunk(e.getKey(), e.getValue().calcScore()))
                .filter(Objects::nonNull)
                .toList();

        if (extraChunks.isEmpty()) return hits;

        // 4. 原结果在前，关联推荐在后
        List<ScoredChunk> result = new ArrayList<>(hits);
        result.addAll(extraChunks);

        log.info("图谱扩展: 原始={}, 关联推荐={}, 总={}", hits.size(), extraChunks.size(), result.size());
        return result;
    }

    /** 从关系类型字符串中提取最优类型（取权重最高的） */
    private String extractBestType(String relTypes) {
        if (relTypes == null || relTypes.isBlank()) return "REFERENCES";
        String[] types = relTypes.trim().split("\\s+");
        String best = types[0];
        double bestWeight = RELATION_WEIGHT.getOrDefault(best, DEFAULT_WEIGHT);
        for (int i = 1; i < types.length; i++) {
            double w = RELATION_WEIGHT.getOrDefault(types[i], DEFAULT_WEIGHT);
            if (w > bestWeight) {
                bestWeight = w;
                best = types[i];
            }
        }
        return best;
    }

    /** 捞取关联文档的第一个 chunk */
    private ScoredChunk fetchChunk(Long docId, double score) {
        try {
            Optional<DocumentChunk> chunk = chunkRepository.findFirstByDocIdOrderBySeqAsc(docId);
            if (chunk.isPresent()) {
                DocumentChunk c = chunk.get();
                return new ScoredChunk(
                        c.getId(), c.getDocId(), c.getSeq(),
                        "", c.getContent() != null ? c.getContent() : "",
                        score, "graph");
            }
        } catch (Exception e) {
            log.warn("关联文档 chunk 拉取失败（docId={}）: {}", docId, e.getMessage());
        }
        return null;
    }

    /** 关联文档评分信息 */
    private static class RelatedInfo {
        final double baseScore;
        final String bestType;
        final int hops;

        RelatedInfo(double baseScore, String bestType, int hops) {
            this.baseScore = baseScore;
            this.bestType = bestType;
            this.hops = hops;
        }

        /** 最终评分 = baseScore × typeWeight ^ hops */
        double calcScore() {
            double weight = RELATION_WEIGHT.getOrDefault(bestType, DEFAULT_WEIGHT);
            return baseScore * Math.pow(weight, hops);
        }
    }
}
