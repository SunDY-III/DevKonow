package com.devknow.chat;

import com.devknow.cache.SemanticCacheService;
import com.devknow.governance.SensitiveWordFilter;
import com.devknow.governance.TokenAuditService;
import com.devknow.rag.HallucinationGuard;
import com.devknow.rag.RagResult;
import com.devknow.rag.RagService;
import com.devknow.vector.ScoredChunk;
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
    private final HallucinationGuard hallucinationGuard;
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

        // 如果 chunks 包含方法级结构化上下文（格式含【方法】/【类】/【签名】等），
        // buildContext 保留原始结构化内容，不扁平化覆盖
        boolean isMethodLevel = context.contains("【方法】") || context.contains("【签名】");

        String systemPrompt = isMethodLevel
                ? ("""
                你是资深开发者代码助手。请基于下方检索到的方法级代码上下文回答用户的问题。
                回答规则：
                1. 优先使用【方法体】中的完整代码来分析逻辑；
                2. 给出具体的文件名、类名、方法名和行号；
                3. 解释逻辑时附上调用链分析（谁调了谁）；
                4. 引用格式：【方法】类名.方法名 或 【文件】文件名:行号；
                5. 如果代码片段不足以回答，请明确说明缺什么信息；
                6. 涉及历史故障或相关提交时请一并指出。
                ===== 检索到的代码上下文 =====
                """ + context)
                : ("""
                你是资深开发者代码助手。请基于下方代码片段和知识库内容回答用户的问题。
                回答规则：
                1. 给出具体的文件名、方法名、行号；
                2. 解释逻辑时附上调用链分析（谁调了谁）；
                3. 引用格式：[文件:行号] 或 [片段n]；
                4. 如果代码片段不足以回答，请明确说明缺什么信息；
                5. 涉及历史故障或相关提交时请一并指出；
                6. 如果检索到的代码缺乏注释、命名不规范或有拼音命名，请根据上下文和代码逻辑推测其功能并在回答中说明。
                ===== 检索到的内容 =====
                """ + context);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
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

                        // === 幻觉第二关 + 第三关：事实验证 + 引用追溯 ===
                        List<ScoredChunk> sourceChunks = rag.getChunks();
                        String hallucinationChecked = hallucinationGuard.executeCheckpoint2And3(safeAnswer, sourceChunks);

                        // 如果验证后文本有变更，推送 corrected 事件供前端更新
                        if (!hallucinationChecked.equals(safeAnswer)) {
                            send(emitter, closed, "corrected", hallucinationChecked);
                            log.info("幻觉检查修正了回答: 原长度={}, 修正后长度={}",
                                    safeAnswer.length(), hallucinationChecked.length());
                        }

                        // 记忆落库 + 语义缓存回填（记录来源 docId 供联动失效）
                        memoryService.append(conversationId, userId, userMessage, AiMessage.from(hallucinationChecked));
                        List<Long> docIds = sourceChunks.stream().map(ScoredChunk::getDocId).distinct().toList();
                        semanticCacheService.put(question, hallucinationChecked, queryVector, docIds);

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
