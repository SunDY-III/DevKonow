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
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话服务 - 两级分类 + 策略模式路由。
 *
 * <p>第一级路由：code / doc / both / unknown
 * 第二级路由：在每个分类内根据上下文进一步细分策略
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

    @Value("${app.rag.method-level-retrieve:false}")
    private boolean methodLevelRetrieve;

    private static final Long FALLBACK_PROJECT_ID = 0L;

    // ==================== 策略路由映射 ====================

    /** 路由 -> 处理策略 */
    private final Map<String, RouteStrategy> routeStrategies = new LinkedHashMap<>();

    {
        routeStrategies.put("code", new CodeRouteStrategy());
        routeStrategies.put("doc", new DocRouteStrategy());
        routeStrategies.put("both", new BothRouteStrategy());
        // "unknown" 没有显式注册，走默认兜底
    }

    /** 策略接口 */
    private interface RouteStrategy {
        void handle(Long userId, String conversationId, String question,
                    float[] queryVector, SseEmitter emitter, AtomicBoolean closed,
                    Long projectId, String context);
    }

    // ==================== 二级策略：代码路由 ====================

    private class CodeRouteStrategy implements RouteStrategy {
        @Override
        public void handle(Long userId, String conversationId, String question,
                           float[] queryVector, SseEmitter emitter, AtomicBoolean closed,
                           Long projectId, String context) {
            try {
                // 二级分类：方法查询 / 调用链 / 实现细节
                String subRoute = classifyCodeSubRoute(question);
                log.info("代码路由二级分类: subRoute={}, q={}", subRoute, question);
                send(emitter, closed, "phase", "code:" + subRoute);

                // 方法级检索：当配置开关打开且查询涉及方法时，使用三路渐进架构
                List<ScoredChunk> results;
                if (methodLevelRetrieve && ("method".equals(subRoute) || "callchain".equals(subRoute))) {
                    // methodLevelRetrieve 待实现，当前降级为普通 code 检索
                    results = ragService.retrieveCode(userId, projectId, question);
                } else {
                    results = ragService.retrieveCode(userId, projectId, question);
                }

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

        private String classifyCodeSubRoute(String question) {
            String response = chatModel.generate("""
                    你是一个代码问题二级分类器。判断用户关于代码的问题属于哪一类。
                    只返回一个词：method / callchain / implementation / logic

                    规则：
                    - method：查找方法在哪、方法签名、参数
                    - callchain：调用链、谁调了谁、影响范围
                    - implementation：具体实现逻辑、算法细节
                    - logic：业务逻辑理解、流程分析

                    问题：""" + question);
            String result = response != null ? response.strip().toLowerCase() : "implementation";
            if (result.contains("method")) return "method";
            if (result.contains("callchain")) return "callchain";
            if (result.contains("implementation")) return "implementation";
            return "logic";
        }
    }

    // ==================== 二级策略：文档路由 ====================

    private class DocRouteStrategy implements RouteStrategy {
        @Override
        public void handle(Long userId, String conversationId, String question,
                           float[] queryVector, SseEmitter emitter, AtomicBoolean closed,
                           Long projectId, String context) {
            try {
                String subRoute = classifyDocSubRoute(question);
                log.info("文档路由二级分类: subRoute={}, q={}", subRoute, question);
                send(emitter, closed, "phase", "doc:" + subRoute);

                RagResult rag = ragService.levelAwareRetrieve(userId, projectId, question);
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

        private String classifyDocSubRoute(String question) {
            String response = chatModel.generate("""
                    你是一个文档问题二级分类器。判断用户关于文档的问题属于哪一类。
                    只返回一个词：architecture / design / api / specification

                    规则：
                    - architecture：架构设计、模块划分、技术选型
                    - design：设计方案、设计文档、ADR
                    - api：接口文档、API 协议、参数说明
                    - specification：规范文档、编码规范、流程规范

                    问题：""" + question);
            String result = response != null ? response.strip().toLowerCase() : "architecture";
            if (result.contains("architecture")) return "architecture";
            if (result.contains("design")) return "design";
            if (result.contains("api")) return "api";
            return "specification";
        }
    }

    // ==================== 二级策略：混合路由 ====================

    private class BothRouteStrategy implements RouteStrategy {
        @Override
        public void handle(Long userId, String conversationId, String question,
                           float[] queryVector, SseEmitter emitter, AtomicBoolean closed,
                           Long projectId, String context) {
            try {
                send(emitter, closed, "phase", "both:merge");
                List<ScoredChunk> codeResults = ragService.retrieveCode(userId, projectId, question);
                RagResult docResults = ragService.levelAwareRetrieve(userId, projectId, question);

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
    }

    // ==================== 入口 ====================

    @Async
    public void streamChat(Long userId, String conversationId, String question,
                           SseEmitter emitter) {
        streamChat(userId, conversationId, question, emitter, null, null);
    }

    @Async
    public void streamChat(Long userId, String conversationId, String question,
                           SseEmitter emitter, Long projectId, String context) {
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
                send(emitter, closed, "cache", "true");
                send(emitter, closed, "token", cached.get().getAnswer());
                send(emitter, closed, "done", "");
                emitter.complete();
                return;
            }

            // 第一级路由
            String route = classifyQuestion(question);
            log.info("一级路由结果: route={}, q={}, projectId={}, context={}", route, question, projectId, context);
            send(emitter, closed, "route", route);

            // 发送项目上下文
            if (projectId != null) {
                send(emitter, closed, "chunk", "{\"projectId\":" + projectId + "}");
            }

            // 策略路由分发
            RouteStrategy strategy = routeStrategies.get(route);
            if (strategy != null) {
                strategy.handle(userId, conversationId, question, queryVector, emitter, closed, projectId, context);
            } else {
                handleAgentRoute(userId, conversationId, question, emitter, closed);
            }

        } catch (Exception e) {
            log.error("chat failed", e);
            send(emitter, closed, "error", "服务繁忙，请稍后再试");
            emitter.complete();
        }
    }

    // ==================== 第一级分类 ====================

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

    // ==================== 兜底：Agent 路由 ====================

    private void handleAgentRoute(Long userId, String conversationId, String question,
                                   SseEmitter emitter, AtomicBoolean closed) {
        String agentReply = codeReviewAgentService.analyze(userId, FALLBACK_PROJECT_ID, conversationId, question);
        send(emitter, closed, "agent", agentReply);
        send(emitter, closed, "token", agentReply);
        send(emitter, closed, "done", "");
        emitter.complete();
    }

    // ==================== SSE 发送 ====================

    private void send(SseEmitter emitter, AtomicBoolean closed, String event, String data) {
        if (closed.get()) return;
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            closed.set(true);
        }
    }
}
