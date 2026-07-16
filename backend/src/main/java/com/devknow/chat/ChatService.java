package com.devknow.chat;

import com.devknow.cache.SemanticCacheService;
import com.devknow.codereview.CodeReviewAgentService;
import com.devknow.governance.SensitiveWordFilter;
import com.devknow.rag.RagResult;
import com.devknow.rag.RagService;
import com.devknow.vector.ScoredChunk;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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

            String route = classifyQuestion(question);
            log.info("LLM 路由结果: route={}, q={}", route, question);
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

    private void handleDocRoute(Long userId, String conversationId, String question,
                                 float[] queryVector, SseEmitter emitter, AtomicBoolean closed) {
        try {
            RagResult rag = ragService.levelAwareRetrieve(userId, question);
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

    /** 双通道融合：代码 + 文档同时搜，合并后交 LLM 自行判断 */
    private void handleBothRoute(Long userId, String conversationId, String question,
                                  float[] queryVector, SseEmitter emitter, AtomicBoolean closed) {
        try {
            List<ScoredChunk> codeResults = ragService.retrieveCode(userId, question);
            RagResult docResults = ragService.levelAwareRetrieve(userId, question);

            Set<Long> seenIds = new HashSet<>();
            List<ScoredChunk> merged = new ArrayList<>();
            if (codeResults != null) {
                for (ScoredChunk c : codeResults) {
                    if (seenIds.add(c.getChunkId())) merged.add(c);
                }
            }
            if (docResults.getChunks() != null) {
                for (ScoredChunk c : docResults.getChunks()) {
                    if (seenIds.add(c.getChunkId())) merged.add(c);
                }
            }

            if (merged.isEmpty()) {
                handleAgentRoute(userId, conversationId, question, emitter, closed);
                return;
            }

            // 不设阈值截断，由 LLM 自行判断哪些内容有用
            double confidence = Math.max(
                    codeResults.isEmpty() ? 0 : codeResults.get(0).getScore(),
                    docResults.getConfidence());
            RagResult combined = new RagResult(merged, confidence);
            llmStreamingService.generateWithFallback(userId, conversationId, question, combined, emitter, closed, queryVector);

        } catch (Exception e) {
            log.warn("both route failed", e);
            handleAgentRoute(userId, conversationId, question, emitter, closed);
        }
    }

    private void handleAgentRoute(Long userId, String conversationId, String question,
                                   SseEmitter emitter, AtomicBoolean closed) {
        String agentReply = codeReviewAgentService.analyze(userId, projectId, conversationId, question);
        send(emitter, closed, "agent", agentReply);
        send(emitter, closed, "token", agentReply);
        send(emitter, closed, "done", "");
        emitter.complete();
    }

    private void send(SseEmitter emitter, AtomicBoolean closed, String event, String data) {
        if (closed.get()) return;
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            closed.set(true);
        }
    }
}
