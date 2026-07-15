package com.zhishu.chat;

import com.zhishu.cache.SemanticCacheService;
import com.zhishu.codereview.CodeReviewAgentService;
import com.zhishu.governance.SensitiveWordFilter;
import com.zhishu.rag.RagResult;
import com.zhishu.rag.RagService;
import com.zhishu.vector.ScoredChunk;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话主链路（LLM 路由版）。
 *
 * <p>路由决策：
 * <ol>
 *   <li>敏感词过滤</li>
 *   <li>语义缓存命中 → 直接返回</li>
 *   <li>LLM 分类问题 → code/doc/both/unknown</li>
 *   <li>按分类走对应通道 → 置信度路由 → LLM 生成 / Agent 兜底</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final RagService ragService;
    private final SemanticCacheService semanticCacheService;
    private final CodeReviewAgentService codeReviewAgentService;
    private final SensitiveWordFilter sensitiveWordFilter;
    private final LlmStreamingService llmStreamingService;
    private final ChatLanguageModel chatModel;

    @Value("${app.rag.confidence-threshold}")
    private double confidenceThreshold;

    private Long projectId = 0L;

    public void setProjectId(Long projectId) {
        this.projectId = projectId != null ? projectId : 0L;
    }

    @Async
    public void streamChat(Long userId, String conversationId, String question, SseEmitter emitter) {
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> { closed.set(true); emitter.complete(); });
        emitter.onError(e -> closed.set(true));

        try {
            if (sensitiveWordFilter.containsSensitive(question)) {
                send(emitter, closed, "error", "您的输入包含敏感内容，请修改后重试");
                emitter.complete();
                return;
            }

            float[] queryVector = ragService.embed(userId, question);
            var cached = semanticCacheService.lookup(queryVector);
            if (cached.isPresent()) {
                send(emitter, closed, "cached", "true");
                send(emitter, closed, "token", cached.get().getAnswer());
                send(emitter, closed, "done", "");
                emitter.complete();
                return;
            }

            // === LLM 路由：判断问题类型 ===
            String route = classifyQuestion(question);
            log.info("LLM 路由结果: route={}, q={}", route, question);

            // 推送路由信息到前端
            send(emitter, closed, "route", route);

            switch (route) {
                case "code" -> handleCodeRoute(userId, conversationId, question, queryVector, emitter, closed);
                case "doc"  -> handleDocRoute(userId, conversationId, question, queryVector, emitter, closed);
                case "both" -> handleBothRoute(userId, conversationId, question, queryVector, emitter, closed);
                default     -> handleAgentRoute(userId, conversationId, question, emitter, closed);
            }

        } catch (Exception e) {
            log.error("chat failed", e);
            send(emitter, closed, "error", "服务繁忙，请稍后再试");
            emitter.complete();
        }
    }

    // ======================== LLM 路由分类 ========================

    /**
     * 用 LLM 判断问题属于哪个通道。
     * 一次极短的 LLM 调用（~50 token 输入，1 token 输出）。
     */
    private String classifyQuestion(String question) {
        String response = chatModel.generate("""
                你是一个路由分类器。判断用户问题应该搜索哪个数据源。
                只返回一个词：code / doc / both / unknown

                规则：
                - code：涉及方法名、调用链、代码逻辑、实现细节
                - doc：涉及设计文档、接口协议、技术选型、为什么用某个技术
                - both：两者都可能相关（如"订单超时怎么处理"——代码里有逻辑，文档里有设计）
                - unknown：不确定或不相关

                问题：""" + question);
        String result = response != null ? response.strip().toLowerCase() : "unknown";
        if (result.contains("code")) return "code";
        if (result.contains("doc")) return "doc";
        if (result.contains("both")) return "both";
        return "unknown";
    }

    // ======================== 路由处理 ========================

    /** 代码通道：搜方法定义、调用链、代码变更 */
    private void handleCodeRoute(Long userId, String conversationId, String question,
                                  float[] queryVector, SseEmitter emitter, AtomicBoolean closed) {
        try {
            List<ScoredChunk> results = ragService.retrieveCode(userId, question);
            if (!results.isEmpty() && results.get(0).getScore() >= confidenceThreshold) {
                RagResult rag = new RagResult(results, results.get(0).getScore());
                llmStreamingService.generateWithFallback(userId, conversationId, question, rag, emitter, closed, queryVector);
            } else {
                handleAgentRoute(userId, conversationId, question, emitter, closed);
            }
        } catch (Exception e) {
            log.warn("code route failed", e);
            handleAgentRoute(userId, conversationId, question, emitter, closed);
        }
    }

    /** 文档通道：搜设计文档、接口协议 */
    private void handleDocRoute(Long userId, String conversationId, String question,
                                 float[] queryVector, SseEmitter emitter, AtomicBoolean closed) {
        try {
            RagResult rag = ragService.retrieve(userId, question);
            if (rag.getConfidence() >= confidenceThreshold) {
                llmStreamingService.generateWithFallback(userId, conversationId, question, rag, emitter, closed, queryVector);
            } else {
                handleAgentRoute(userId, conversationId, question, emitter, closed);
            }
        } catch (Exception e) {
            log.warn("doc route failed", e);
            handleAgentRoute(userId, conversationId, question, emitter, closed);
        }
    }

    /** 双通道融合：代码 + 文档同时搜，RRF 融合 */
    private void handleBothRoute(Long userId, String conversationId, String question,
                                  float[] queryVector, SseEmitter emitter, AtomicBoolean closed) {
        try {
            List<ScoredChunk> codeResults = ragService.retrieveCode(userId, question);
            RagResult docResults = ragService.retrieve(userId, question);
            if (!codeResults.isEmpty() && codeResults.get(0).getScore() >= confidenceThreshold) {
                RagResult rag = new RagResult(codeResults, codeResults.get(0).getScore());
                llmStreamingService.generateWithFallback(userId, conversationId, question, rag, emitter, closed, queryVector);
            } else if (docResults.getConfidence() >= confidenceThreshold) {
                llmStreamingService.generateWithFallback(userId, conversationId, question, docResults, emitter, closed, queryVector);
            } else {
                handleAgentRoute(userId, conversationId, question, emitter, closed);
            }
        } catch (Exception e) {
            log.warn("both route failed", e);
            handleAgentRoute(userId, conversationId, question, emitter, closed);
        }
    }

    /** Agent 兜底：搜不到时直接分析代码 */
    private void handleAgentRoute(Long userId, String conversationId, String question,
                                   SseEmitter emitter, AtomicBoolean closed) {
        String agentReply = codeReviewAgentService.analyze(userId, projectId, conversationId, question);
        send(emitter, closed, "ticket", "agent");
        send(emitter, closed, "token", agentReply);
        send(emitter, closed, "done", "");
        emitter.complete();
    }

    // ======================== SSE 推送 ========================

    private void send(SseEmitter emitter, AtomicBoolean closed, String event, String data) {
        if (closed.get()) return;
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            closed.set(true);
        }
    }
}
