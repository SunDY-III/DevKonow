package com.devknow.agent;

import com.devknow.agent.tool.ObservationCompressor;
import com.devknow.agent.tool.SearchCodeTool;
import com.devknow.agent.tool.SearchContext;
import com.devknow.agent.tool.SearchDocTool;
import com.devknow.agent.tool.SearchGraphTool;
import com.devknow.cache.AgentExperienceCache;
import com.devknow.cache.AgentExperienceCache.AgentCacheEntry;
import com.devknow.governance.SensitiveWordFilter;
import com.devknow.governance.TokenAuditService;
import com.devknow.knowledge.graph.KnowledgeGraphService;
import com.devknow.rag.HallucinationGuard;
import com.devknow.rag.RagService;
import com.devknow.vector.ScoredChunk;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ReAct (Reasoning + Acting) 迭代检索 Agent。
 *
 * <p>基于 LangChain4j {@code AiServices} + {@code @Tool} 原生方式实现，
 * 由框架自动管理"思考→工具调用→观察→回答"的 ReAct 循环。
 *
 * <h3>与之前手写 ReAct 的区别</h3>
 * <ul>
 *   <li><b>之前</b>：手写 for 循环 + XML 正则解析 {@code <thought>/<action>/<action_input>}</li>
 *   <li><b>现在</b>：{@code AiServices.builder().tools(...).build()} 框架自动管理循环，
 *       工具调用走结构化 JSON（function calling），不再解析文本格式</li>
 * </ul>
 *
 * <p>设计要点：
 * <ul>
 *   <li>工具实例和 {@link SearchContext} 每次请求创建，天然隔离</li>
 *   <li>流式模型处理最终回答，逐 token SSE 推送</li>
 *   <li>完成后执行幻觉检查 C2+C3、敏感词过滤、Token 计费</li>
 *   <li>并发控制复用 {@code llmConcurrencyLimiter} 信号量</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReActAgent {

    private final StreamingChatLanguageModel streamingChatLanguageModel;
    private final RagService ragService;
    private final KnowledgeGraphService knowledgeGraphService;
    private final HallucinationGuard hallucinationGuard;
    private final SensitiveWordFilter sensitiveWordFilter;
    private final TokenAuditService tokenAuditService;
    private final Semaphore llmConcurrencyLimiter;
    private final ObservationCompressor observationCompressor;
    private final AgentExperienceCache agentExperienceCache;

    @Value("${app.agent.max-tool-rounds:6}")
    private int maxToolRounds;

    // ==================== 主入口 ====================

    /**
     * 使用 AiServices + @Tool 处理用户问题。
     *
     * @param userId         用户 ID
     * @param conversationId 会话 ID（当前未使用，AiServices 内置 ChatMemory）
     * @param question       用户问题
     * @param projectId      项目 ID（可为 null）
     * @param emitter        SSE 发射器
     * @param closed         连接关闭标记
     */
    public void react(Long userId, String conversationId, String question,
                      Long projectId, SseEmitter emitter, AtomicBoolean closed) {
        long startNanos = System.nanoTime();

        // 1. 每次请求创建隔离的工具上下文和工具实例
        SearchContext searchContext = new SearchContext();

        // 1b. 检查 Agent 经验缓存（参考 K3 Mooncake >90% 缓存命中）
        //     同类问题直接复用检索结果，跳过工具调用循环
        var cached = agentExperienceCache.lookup(0, "react", question);
        if (cached.isPresent()) {
            AgentCacheEntry entry = cached.get();
            log.info("Agent经验缓存命中: level={}, toolCalls={}次→跳过检索",
                    entry.getLevel(), entry.getToolCalls());

            List<ScoredChunk> cachedChunks = entry.toChunks();
            for (ScoredChunk c : cachedChunks) {
                searchContext.addChunk(c);
            }

            sendEvent(emitter, closed, "route", "react");
            sendEvent(emitter, closed, "phase", "react:cache_hit");
            sendEvent(emitter, closed, "source", toSourceJson(cachedChunks));
            sendEvent(emitter, closed, "token", entry.getAnswer());
            sendEvent(emitter, closed, "done", "");
            emitter.complete();

            log.info("ReAct Agent 缓存命中: elapsed={}ms", (System.nanoTime() - startNanos) / 1_000_000);
            return;
        }
        SearchCodeTool codeTool = new SearchCodeTool(ragService, userId, projectId, searchContext, observationCompressor);
        SearchDocTool docTool = new SearchDocTool(ragService, userId, projectId, searchContext, observationCompressor);
        SearchGraphTool graphTool = new SearchGraphTool(knowledgeGraphService, searchContext, observationCompressor);

        // 2. 构建 AiServices —— 框架自动管理 ReAct 循环
        //     限制 ChatMemory 窗口大小间接控制工具调用轮数
        ChatMemory chatMemory = MessageWindowChatMemory.withMaxMessages(maxToolRounds * 3);
        ReActAssistant assistant = AiServices.builder(ReActAssistant.class)
                .streamingChatLanguageModel(streamingChatLanguageModel)
                .chatMemory(chatMemory)
                .tools(codeTool, docTool, graphTool)
                .build();

        // 3. SSE 事件：通知前端进入 ReAct 模式
        sendEvent(emitter, closed, "route", "react");
        sendEvent(emitter, closed, "phase", "react:reasoning");

        // 4. 并发控制
        boolean acquired = llmConcurrencyLimiter.tryAcquire();
        if (!acquired) {
            sendEvent(emitter, closed, "error", "当前提问人数较多，请稍后再试");
            emitter.complete();
            return;
        }

        // 5. 流式调用 AiServices（工具调用循环在框架内部非流式执行，
        //    只有在最终生成文本时才流式输出 token）
        StringBuilder answerBuilder = new StringBuilder();
        try {
            TokenStream stream = assistant.chat(question);

            stream.onNext(token -> {
                if (closed.get()) return;
                answerBuilder.append(token);
                sendEvent(emitter, closed, "token", token);
            }).onComplete(response -> {
                llmConcurrencyLimiter.release();
                try {
                    handleCompletion(userId, question, searchContext,
                            answerBuilder.toString(), response, emitter, closed);
                } finally {
                    emitter.complete();
                }
            }).onError(error -> {
                llmConcurrencyLimiter.release();
                log.error("ReAct Agent 流式生成错误", error);
                sendEvent(emitter, closed, "error", "模型服务异常，请稍后重试");
                emitter.complete();
            }).start();

        } catch (Exception e) {
            llmConcurrencyLimiter.release();
            log.error("ReAct Agent 调用异常", e);
            sendEvent(emitter, closed, "error", "服务繁忙，请稍后再试");
            emitter.complete();
        }

        long elapsed = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("ReAct Agent 完成: elapsed={}ms, chunks={}", elapsed, searchContext.getAllChunks().size());
    }

    // ==================== 完成处理 ====================

    /**
     * 流式完成后处理：幻觉检查 + 引用推送 + Token 计费。
     */
    private void handleCompletion(Long userId, String question, SearchContext searchContext,
                                   String rawAnswer,
                                   dev.langchain4j.model.output.Response<dev.langchain4j.data.message.AiMessage> response,
                                   SseEmitter emitter, AtomicBoolean closed) {
        if (closed.get()) return;

        List<ScoredChunk> chunks = searchContext.getAllChunks();

        // 敏感词过滤
        String safeAnswer = sensitiveWordFilter.replaceSensitive(rawAnswer);

        // Token 计费
        if (response != null && response.tokenUsage() != null) {
            tokenAuditService.record(userId, "REACT_CHAT",
                    response.tokenUsage().inputTokenCount(),
                    response.tokenUsage().outputTokenCount());
        }

        // 推送引用来源（前端展示出处）
        sendEvent(emitter, closed, "source", toSourceJson(chunks));

        // 幻觉检查 C2 + C3
        String hallucinationChecked = hallucinationGuard.executeCheckpoint2And3(safeAnswer, chunks);
        if (!hallucinationChecked.equals(safeAnswer)) {
            sendEvent(emitter, closed, "corrected", hallucinationChecked);
            log.info("幻觉检查修正了回答: 原长度={}, 修正后长度={}",
                    safeAnswer.length(), hallucinationChecked.length());
        }

        // 保存 Agent 经验缓存（供后续同类查询复用检索路径）
        int toolCallEstimate = chunks.size() > 0 ? Math.min(chunks.size() / 2 + 1, 6) : 0;
        agentExperienceCache.save(0, "react", question, searchContext,
                toolCallEstimate, hallucinationChecked);

        // 完成信号
        sendEvent(emitter, closed, "done", "");
    }

    // ==================== SSE 工具 ====================

    private void sendEvent(SseEmitter emitter, AtomicBoolean closed, String event, String data) {
        if (closed.get()) return;
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            closed.set(true);
        }
    }

    private String toSourceJson(List<ScoredChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < chunks.size(); i++) {
            ScoredChunk c = chunks.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"index\":").append(i + 1)
                    .append(",\"file\":\"")
                    .append(c.getFileName() != null ? c.getFileName().replace("\"", "") : "unknown")
                    .append("\",\"seq\":").append(c.getSeq() != null ? c.getSeq() : 0)
                    .append('}');
        }
        return sb.append(']').toString();
    }
}
