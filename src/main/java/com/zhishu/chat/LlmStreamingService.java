package com.zhishu.chat;

import com.zhishu.cache.SemanticCacheService;
import com.zhishu.governance.SensitiveWordFilter;
import com.zhishu.governance.TokenAuditService;
import com.zhishu.rag.RagResult;
import com.zhishu.rag.RagService;
import com.zhishu.vector.ScoredChunk;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式生成 + 熔断降级。
 *
 * <p>为什么单独成类（面试点）：{@code @CircuitBreaker} 依赖 Spring AOP 代理织入，
 * 而代理对“同一个 Bean 内部的自调用”不生效。原先该方法写在 {@code ChatService} 里、
 * 由同类的 {@code streamChat} 直接 {@code this.generateWithFallback(...)} 调用，
 * 走的是原始对象而非代理，熔断与 fallback 形同虚设。
 * 把它抽到独立 Bean 后，{@code ChatService} 注入并跨 Bean 调用，调用链经过代理，注解才真正生效。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmStreamingService {

    private final RagService ragService;
    private final MemoryService memoryService;
    private final StreamingChatLanguageModel streamingModel;
    private final SensitiveWordFilter sensitiveWordFilter;
    private final TokenAuditService tokenAuditService;
    private final SemanticCacheService semanticCacheService;
    private final Semaphore llmConcurrencyLimiter;

    /**
     * 熔断降级（面试点）：模型 API 连续失败时熔断器打开，直接走 fallback ——
     * 返回纯检索结果（不生成），保证“答案可能糙但服务可用”。
     */
    @CircuitBreaker(name = "llm", fallbackMethod = "retrievalOnlyFallback")
    public void generateWithFallback(Long userId, String conversationId, String question,
                                     RagResult rag, SseEmitter emitter, AtomicBoolean closed,
                                     float[] queryVector) throws Exception {
        String context = ragService.buildContext(rag.getChunks());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("""
                你是企业知识库助手。请严格基于下方知识库片段回答用户问题：
                1. 只使用片段中的信息，不要编造；
                2. 回答末尾用 [片段n] 标注引用了哪些片段；
                3. 片段不足以回答时明确说明。
                ===== 知识库片段 =====
                """ + context));
        messages.addAll(memoryService.load(conversationId));   // 多轮记忆
        UserMessage userMessage = UserMessage.from(question);
        messages.add(userMessage);

        // 先推送引用来源，前端边收 token 边展示出处
        send(emitter, closed, "source", toSourceJson(rag.getChunks()));

        StringBuilder answer = new StringBuilder();
        boolean acquired = llmConcurrencyLimiter.tryAcquire();
        if (!acquired) {
            send(emitter, closed, "error", "当前提问人数较多，请稍后再试");
            emitter.complete();
            return;
        }
        try {
            streamingModel.generate(messages, new dev.langchain4j.model.StreamingResponseHandler<AiMessage>() {
                @Override
                public void onNext(String token) {
                    // 断连后停止写出，避免向已关闭的连接持续 IO
                    if (closed.get()) return;
                    answer.append(token);
                    send(emitter, closed, "token", token);
                }

                @Override
                public void onComplete(Response<AiMessage> response) {
                    llmConcurrencyLimiter.release();
                    try {
                        String fullAnswer = answer.toString();
                        // 输出侧敏感词兜底
                        String safeAnswer = sensitiveWordFilter.replaceSensitive(fullAnswer);

                        // Token 计费审计（流式场景在 onComplete 拿真实用量）
                        if (response.tokenUsage() != null) {
                            tokenAuditService.record(userId, "CHAT",
                                    response.tokenUsage().inputTokenCount(),
                                    response.tokenUsage().outputTokenCount());
                        }
                        // 记忆落库 + 语义缓存回填（记录来源 docId 供联动失效）
                        memoryService.append(conversationId, userId, userMessage, AiMessage.from(safeAnswer));
                        List<Long> docIds = rag.getChunks().stream().map(ScoredChunk::getDocId).distinct().toList();
                        semanticCacheService.put(question, safeAnswer, queryVector, docIds);

                        send(emitter, closed, "done", "");
                    } finally {
                        emitter.complete();   // 正常结束释放连接
                    }
                }

                @Override
                public void onError(Throwable error) {
                    llmConcurrencyLimiter.release();
                    log.error("llm stream error", error);
                    send(emitter, closed, "error", "模型服务异常，已为您保留问题，可稍后重试");
                    emitter.complete();       // 异常路径同样必须释放，否则连接泄漏
                }
            });
        } catch (Exception e) {
            llmConcurrencyLimiter.release();
            throw e;
        }
    }

    /** 熔断打开时的降级：直接返回检索片段，不调用 LLM */
    @SuppressWarnings("unused")
    public void retrievalOnlyFallback(Long userId, String conversationId, String question,
                                      RagResult rag, SseEmitter emitter, AtomicBoolean closed,
                                      float[] queryVector, Throwable t) {
        log.warn("circuit OPEN, retrieval-only fallback", t);
        send(emitter, closed, "source", toSourceJson(rag.getChunks()));
        StringBuilder sb = new StringBuilder("【降级模式】模型服务暂不可用，以下是知识库中最相关的内容：\n\n");
        rag.getChunks().forEach(c -> sb.append("• ").append(c.getContent()).append('\n'));
        send(emitter, closed, "token", sb.toString());
        send(emitter, closed, "done", "");
        emitter.complete();
    }

    private void send(SseEmitter emitter, AtomicBoolean closed, String event, String data) {
        if (closed.get()) return;
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            closed.set(true);   // 写失败视为断连，停止后续推送
        }
    }

    private String toSourceJson(List<ScoredChunk> chunks) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < chunks.size(); i++) {
            ScoredChunk c = chunks.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"index\":").append(i + 1)
              .append(",\"file\":\"").append(c.getFileName().replace("\"", ""))
              .append("\",\"seq\":").append(c.getSeq()).append('}');
        }
        return sb.append(']').toString();
    }
}
