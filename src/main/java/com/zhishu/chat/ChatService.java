package com.zhishu.chat;

import com.zhishu.cache.SemanticCacheService;
import com.zhishu.codereview.CodeReviewAgentService;
import com.zhishu.governance.SensitiveWordFilter;
import com.zhishu.rag.RagResult;
import com.zhishu.rag.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话主链路。
 *
 * <p>路由决策：
 * <ol>
 *   <li>敏感词过滤</li>
 *   <li>语义缓存命中 → 直接返回</li>
 *   <li>RAG 检索 → 置信度 ≥ 阈值 → LLM 流式生成</li>
 *   <li>RAG 检索 → 置信度 < 阈值 → 代码审查 Agent 兜底</li>
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

    @Value("${app.rag.confidence-threshold}")
    private double confidenceThreshold;

    /** 当前对话的项目 ID（由 Controller 设置） */
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
            // 0. 敏感词拦截
            if (sensitiveWordFilter.containsSensitive(question)) {
                send(emitter, closed, "error", "您的输入包含敏感内容，请修改后重试");
                emitter.complete();
                return;
            }

            // 1. 语义缓存
            float[] queryVector = ragService.embed(userId, question);
            var cached = semanticCacheService.lookup(queryVector);
            if (cached.isPresent()) {
                send(emitter, closed, "cached", "true");
                send(emitter, closed, "token", cached.get().getAnswer());
                send(emitter, closed, "done", "");
                emitter.complete();
                return;
            }

            // 2. RAG 检索
            RagResult rag = ragService.retrieve(userId, question);

            // 3. 置信度路由
            if (rag.getConfidence() < confidenceThreshold) {
                log.info("置信度不足 ({} < {}), 路由到代码审查 Agent",
                        String.format("%.3f", rag.getConfidence()), confidenceThreshold);
                send(emitter, closed, "source", ragService.buildContext(rag.getChunks()));
                String agentReply = codeReviewAgentService.analyze(
                        userId, projectId, conversationId, question);
                send(emitter, closed, "token", agentReply);
                send(emitter, closed, "done", "");
                emitter.complete();
                return;
            }

            // 4. 高置信 → LLM 流式生成
            llmStreamingService.generateWithFallback(
                    userId, conversationId, question, rag, emitter, closed, queryVector);

        } catch (Exception e) {
            log.error("chat failed", e);
            send(emitter, closed, "error", "服务繁忙，请稍后再试");
            emitter.complete();
        }
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
