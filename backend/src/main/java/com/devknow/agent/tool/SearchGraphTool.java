package com.devknow.agent.tool;

import com.devknow.knowledge.graph.KnowledgeGraphService;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 知识图谱搜索工具 —— 供 ReAct Agent 在推理循环中调用。
 *
 * <p>当 LLM 判断需要查找文档间关系、依赖分析、调用关联时，
 * 会触发此工具的 {@link #searchGraph(String)} 方法。
 *
 * <p>注意：知识图谱只记录文档节点间的关联关系，不存储文档正文。
 * 如需文档内容，应配合使用 search_doc 或 search_code。
 */
@Slf4j
public class SearchGraphTool {

    private final KnowledgeGraphService knowledgeGraphService;
    private final SearchContext searchContext;
    private final ObservationCompressor compressor;

    public SearchGraphTool(KnowledgeGraphService knowledgeGraphService,
                            SearchContext searchContext, ObservationCompressor compressor) {
        this.knowledgeGraphService = knowledgeGraphService;
        this.searchContext = searchContext;
        this.compressor = compressor;
    }

    @Tool("Search the knowledge graph for document relationships, dependency analysis, entity connections. " +
          "Use this to understand how documents or modules relate to each other. " +
          "NOTE: The graph stores relationships (references, dependencies), not document content.")
    public String searchGraph(String query) {
        log.info("工具调用 search_graph: query={}", query);

        Map<String, Object> stats;
        try {
            stats = knowledgeGraphService.getStats();
        } catch (Exception e) {
            log.warn("知识图谱获取统计失败: {}", e.getMessage());
            return "知识图谱暂不可用。请使用 search_code 或 search_doc 搜索内容。";
        }

        long nodeCount = (long) stats.getOrDefault("totalNodes", 0L);
        long relationCount = (long) stats.getOrDefault("totalRelations", 0L);

        if (nodeCount == 0) {
            return "知识图谱中暂无数据，请先通过 search_code 或 search_doc 检索内容。\n" +
                   "知识图谱记录文档间的关系，需在文档导入后自动构建。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("知识图谱概况：").append(nodeCount).append(" 个文档节点，")
                .append(relationCount).append(" 条关系。\n\n");

        // 从当前已积累的 chunks 中提取已知文档 ID，探索关联
        Set<Long> knownDocIds = new HashSet<>();
        for (var chunk : searchContext.getAllChunks()) {
            if (chunk.getDocId() != null && chunk.getDocId() > 0) {
                knownDocIds.add(chunk.getDocId());
            }
        }

        if (!knownDocIds.isEmpty()) {
            try {
                var relations = knowledgeGraphService.findRelatedBatch(
                        new ArrayList<>(knownDocIds), 2);
                if (relations != null && !relations.isEmpty()) {
                    sb.append("当前文档关联关系：\n");
                    int count = 0;
                    for (var rel : relations) {
                        if (count++ >= 10) {
                            sb.append("  ...等共 ").append(relations.size()).append(" 条关系\n");
                            break;
                        }
                        sb.append("  · 文档 ").append(rel.getSourceDocId())
                                .append(" ──[").append(rel.getRelationType()).append("]──▶ ")
                                .append("文档 ").append(rel.getDocId())
                                .append(" (跳数:").append(rel.getHops()).append(")\n");
                    }
                    sb.append("\n提示：如需查看某个文档的详细内容，请使用 search_doc 搜索。");
                } else {
                    sb.append("当前文档在知识图谱中暂无关联系。");
                }
            } catch (Exception e) {
                log.warn("知识图谱关联查询失败: {}", e.getMessage());
                sb.append("关联查询暂不可用。");
            }
        } else {
            sb.append("当前上下文中没有已知文档。\n");
            sb.append("请先通过 search_code 或 search_doc 找到相关文档，\n");
            sb.append("再使用 search_graph 探索其关联关系。");
        }

        return compressor.compress("search_graph", query, sb.toString());
    }
}
