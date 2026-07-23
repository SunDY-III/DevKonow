package com.devknow.agent.tool;

import com.devknow.rag.RagResult;
import com.devknow.rag.RagService;
import com.devknow.vector.ScoredChunk;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 文档搜索工具 —— 供 ReAct Agent 在推理循环中调用。
 *
 * <p>当 LLM 判断需要查找架构设计、API 协议、技术选型、规范文档时，
 * 会触发此工具的 {@link #searchDoc(String)} 方法。
 *
 * <p>内部走 DevKnow 的完整层级感知 RAG 管道（L1~L5 层级过滤 + HyDE + MMR + ReRank）。
 */
@Slf4j
public class SearchDocTool {

    private final RagService ragService;
    private final Long userId;
    private final Long projectId;
    private final SearchContext searchContext;
    private final ObservationCompressor compressor;

    public SearchDocTool(RagService ragService, Long userId, Long projectId,
                          SearchContext searchContext, ObservationCompressor compressor) {
        this.ragService = ragService;
        this.userId = userId;
        this.projectId = projectId;
        this.searchContext = searchContext;
        this.compressor = compressor;
    }

    @Tool("Search documentation and knowledge base for architecture design, API specifications, technical decisions, " +
          "design documents, and coding standards. Use this when the user asks about design rationale or documentation.")
    public String searchDoc(String query) {
        log.info("工具调用 search_doc: query={}, userId={}, projectId={}", query, userId, projectId);

        // 使用轻量探索检索（参考 K3 3:1 注意力混合）
        // Agent 早期调用的工具走轻量路径（无 HyDE/MMR/ReRank/图谱扩展）
        // 最终全量检索在 Agent 决定回答时由 LLM 自动整合所有 observation
        RagResult ragResult = ragService.exploreRetrieve(userId, projectId, query);
        List<ScoredChunk> results = ragResult.getChunks();

        if (results == null || results.isEmpty()) {
            return "未找到相关文档。请尝试更换搜索关键词。\n" +
                   "提示：使用更具体的文档主题词。";
        }

        int newCount = searchContext.addChunks(results);
        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(results.size()).append(" 个相关文档片段（置信度:")
                .append(String.format("%.2f", ragResult.getConfidence())).append("）：\n\n");

        for (int i = 0; i < Math.min(results.size(), 5); i++) {
            ScoredChunk c = results.get(i);
            sb.append("【片段 ").append(i + 1).append("】")
                    .append(c.getFileName() != null ? c.getFileName() : "未知文档")
                    .append("\n")
                    .append(c.getContent()).append("\n\n");
        }

        if (results.size() > 5) {
            sb.append("...等共 ").append(results.size()).append(" 条结果").append("\n");
        }
        if (newCount > 0) {
            sb.append("（本次新增 ").append(newCount).append(" 条记录）");
        }

        // ReNAct 压缩
        return compressor.compress("search_doc", query, sb.toString());
    }
}
