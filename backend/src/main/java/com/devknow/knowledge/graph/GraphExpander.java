package com.devknow.knowledge.graph;

import com.devknow.knowledge.DocumentChunk;
import com.devknow.knowledge.DocumentChunkRepository;
import com.devknow.vector.ScoredChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识图谱上下文扩展器。
 *
 * <p>在 RAG 检索结果的基础上，通过 Neo4j 知识图谱发现关联文档，
 * 将相关文档的片段作为额外上下文追加到结果中。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphExpander {

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

        // 1. 从命中结果提取不重复 docId
        Set<Long> hitDocIds = hits.stream()
                .map(ScoredChunk::getDocId)
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .collect(Collectors.toSet());

        if (hitDocIds.isEmpty()) return hits;

        // 2. 遍历命中文档，查 Neo4j 获取关联文档（去重）
        Set<Long> relatedDocIds = new LinkedHashSet<>();
        for (Long docId : hitDocIds) {
            try {
                List<GraphRelationResult> related = graphService.findRelated(docId, maxHops);
                for (GraphRelationResult r : related) {
                    // 排除已经出现在原始结果中的文档
                    if (!hitDocIds.contains(r.getDocId()) && relatedDocIds.size() < maxExtra * 2) {
                        relatedDocIds.add(r.getDocId());
                    }
                }
            } catch (Exception e) {
                log.warn("图谱扩展查询失败（docId={}）: {}", docId, e.getMessage());
            }
        }

        if (relatedDocIds.isEmpty()) return hits;

        // 3. 限制最多取 maxExtra 篇关联文档
        List<Long> selectedIds = relatedDocIds.stream()
                .limit(maxExtra)
                .toList();

        // 4. 从 chunk 库捞取关联文档的片段（每篇取第 1 个 chunk）
        List<ScoredChunk> extraChunks = new ArrayList<>();
        for (Long relatedDocId : selectedIds) {
            try {
                Optional<DocumentChunk> chunk = chunkRepository.findFirstByDocIdOrderBySeqAsc(relatedDocId);
                if (chunk.isPresent()) {
                    DocumentChunk c = chunk.get();
                    extraChunks.add(new ScoredChunk(
                            c.getId(), c.getDocId(), c.getSeq(),
                            "", c.getContent() != null ? c.getContent() : "",
                            0.3  // 固定低分，不冲淡原始结果
                    ));
                }
            } catch (Exception e) {
                log.warn("关联文档 chunk 拉取失败（docId={}）: {}", relatedDocId, e.getMessage());
            }
        }

        if (extraChunks.isEmpty()) return hits;

        // 5. 合并结果：原结果在前，关联推荐在后
        List<ScoredChunk> result = new ArrayList<>(hits);
        result.addAll(extraChunks);

        log.info("图谱扩展: 原始={}, 关联推荐={}, 总={}", hits.size(), extraChunks.size(), result.size());
        return result;
    }
}
