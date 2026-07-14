package com.zhishu.chat;

import com.zhishu.cache.SemanticCacheService;
import com.zhishu.governance.SensitiveWordFilter;
import com.zhishu.rag.RagResult;
import com.zhishu.rag.RagService;
import com.zhishu.ticket.TicketAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话主链路：敏感词过滤 -> 语义缓存 -> RAG 混合检索 -> 置信度路由（低置信转工单 Agent）
 * -> SSE 流式生成 -> 引用溯源 -> 记忆落库 -> 缓存回填。
 *
 * <p>流式生成与熔断降级下沉到 {@link LlmStreamingService}：{@code @CircuitBreaker} 依赖 AOP 代理，
 * 必须跨 Bean 调用才生效，不能写成本类的自调用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final RagService ragService;
    private final SemanticCacheService semanticCacheService;
    private final TicketAgentService ticketAgentService;
    private final SensitiveWordFilter sensitiveWordFilter;
    private final LlmStreamingService llmStreamingService;

    @Value("${app.rag.confidence-threshold}")
    private double confidenceThreshold;

    /**
     * SSE 事件协议：
     *   token  - 增量正文      source - 引用来源 JSON
     *   cached - 命中语义缓存   ticket - 已转工单
     *   done   - 结束          error  - 异常
     */
    @Async
    public void streamChat(Long userId, String conversationId, String question, SseEmitter emitter) {
        // 客户端断连检测：Emitter 完成/超时后置位，生成回调里检查并停止写出
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> { closed.set(true); emitter.complete(); });
        emitter.onError(e -> closed.set(true));

        try {
            // 0. 输入侧敏感词拦截
            if (sensitiveWordFilter.containsSensitive(question)) {
                send(emitter, closed, "error", "您的输入包含敏感内容，请修改后重试");
                emitter.complete();
                return;
            }

            // 1. 语义缓存：相似问题直接返回历史回答（带标识）
            float[] queryVector = ragService.embed(userId, question);
            var cached = semanticCacheService.lookup(queryVector);
            if (cached.isPresent()) {
                send(emitter, closed, "cached", "true");
                send(emitter, closed, "token", cached.get().getAnswer());
                send(emitter, closed, "done", "");
                emitter.complete();
                return;
            }

            // 2. RAG 混合检索
            RagResult rag = ragService.retrieve(userId, question);

            // 3. 置信度路由：知识库答不了 -> 代码检索 + LLM 兜底
            //    （原工单 Agent 流程已在 DevKnow 裁撤，替换为代码审查 Agent）
            if (rag.getConfidence() < confidenceThreshold) {
                log.info("low confidence {}, route to code fallback", rag.getConfidence());
                // Phase 1：尝试代码检索，没有命中再由 LLM 直接回答
                // Phase 3 接入多源检索 + 代码审查 Agent
                llmStreamingService.generateWithFallback(userId, conversationId, question, rag, emitter, closed, queryVector);
                return;
            }

            // 4. 流式生成（信号量控制 LLM 并发 + 熔断降级）。
            //    跨 Bean 调用，确保 @CircuitBreaker 代理生效。
            llmStreamingService.generateWithFallback(userId, conversationId, question, rag, emitter, closed, queryVector);

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
            closed.set(true);   // 写失败视为断连，停止后续推送
        }
    }
}
