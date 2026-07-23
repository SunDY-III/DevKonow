package com.devknow.agent.tool;

import com.devknow.rag.RagService;
import com.devknow.vector.ScoredChunk;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 代码搜索工具 —— 供 ReAct Agent 在推理循环中调用。
 *
 * <p>当 LLM 判断需要查找方法实现、调用链、代码逻辑时，
 * 会触发此工具的 {@link #searchCode(String)} 方法。
 *
 * <p>返回结果会被 {@link ObservationCompressor} 压缩后送入 LLM 上下文，
 * 原始完整结果保留在 {@link SearchContext} 中供幻觉检查使用（ReNAct 模式）。
 */
@Slf4j
public class SearchCodeTool {

    private final RagService ragService;
    private final Long userId;
    private final Long projectId;
    private final SearchContext searchContext;
    private final ObservationCompressor compressor;

    public SearchCodeTool(RagService ragService, Long userId, Long projectId,
                           SearchContext searchContext, ObservationCompressor compressor) {
        this.ragService = ragService;
        this.userId = userId;
        this.projectId = projectId;
        this.searchContext = searchContext;
        this.compressor = compressor;
    }

    @Tool("Search code in the project for method implementations, code logic, call chains, class/interface definitions. " +
          "Use this when the user asks about how something is implemented in code.")
    public String searchCode(String query) {
        log.info("工具调用 search_code: query={}, userId={}, projectId={}", query, userId, projectId);

        List<ScoredChunk> results = ragService.retrieveCode(userId, projectId, query);
        if (results == null || results.isEmpty()) {
            return "未找到相关代码。请尝试更换搜索关键词。\n" +
                   "提示：使用更具体的类名或方法名，如 OrderService.createOrder 而非 订单。";
        }

        int newCount = searchContext.addChunks(results);
        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(results.size()).append(" 个相关代码片段：\n\n");

        for (int i = 0; i < Math.min(results.size(), 5); i++) {
            ScoredChunk c = results.get(i);
            sb.append("【片段 ").append(i + 1).append("】")
                    .append(c.getFileName() != null ? c.getFileName() : "未知文件")
                    .append("（相关性:").append(String.format("%.2f", c.getScore())).append("）\n")
                    .append(c.getContent()).append("\n\n");
        }

        if (results.size() > 5) {
            sb.append("...等共 ").append(results.size()).append(" 条结果").append("\n");
        }
        if (newCount > 0) {
            sb.append("（本次新增 ").append(newCount).append(" 条记录）");
        }

        // ReNAct 压缩：原始全量存入 SearchContext，给 LLM 的 observation 用压缩版
        String rawResult = sb.toString();
        return compressor.compress("search_code", query, rawResult);
    }
}
