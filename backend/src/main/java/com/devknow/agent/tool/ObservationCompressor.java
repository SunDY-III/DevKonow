package com.devknow.agent.tool;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Observation 压缩器 —— ReNAct / NotesWriting 模式的 Java 实现。
 *
 * <p>将工具返回的详细结果压缩为简洁的结构化笔记，减少放入 LLM 上下文的 Token 量。
 * 原始完整结果仍保留在 {@link SearchContext} 中供幻觉检查使用。
 *
 * <p>参考论文：NotesWriting (2025), ReNAct
 * 参考实现：Kimi K3 的 Swarm Agent 笔记机制
 *
 * <p>效果预期（基于论文数据）：
 * <ul>
 *   <li>输入 Token 减少：~70-85%</li>
 *   <li>回答质量：持平或略有提升（去噪声后注意力更集中）</li>
 * </ul>
 */
@Slf4j
@Component
public class ObservationCompressor {

    private final ChatLanguageModel fastModel;

    @Value("${app.rag.observation-compressor.enabled:true}")
    private boolean enabled;

    /** 超过此长度的 observation 才触发压缩（字符数） */
    @Value("${app.rag.observation-compressor.threshold:600}")
    private int lengthThreshold;

    public ObservationCompressor(@Qualifier("fastChatLanguageModel") ChatLanguageModel fastModel) {
        this.fastModel = fastModel;
    }

    /**
     * 压缩工具返回结果。
     *
     * @param toolName  工具名称（"search_code" / "search_doc" / "search_graph"）
     * @param query     触发此次搜索的查询
     * @param rawResult 工具返回的原始文本
     * @return 压缩后的文本（未超过阈值时返回原文）
     */
    public String compress(String toolName, String query, String rawResult) {
        if (!enabled || rawResult == null || rawResult.length() <= lengthThreshold) {
            return rawResult;
        }

        long start = System.nanoTime();

        try {
            String prompt = """
                    你是一个笔记助手。将以下搜索结果压缩为简洁、结构化的笔记。
                    保留关键信息：文件名、方法名、行号、核心结论。
                    去除冗余描述、格式化空白。
                    目标长度：不超过 200 字。使用列表格式。

                    搜索来源：%s
                    搜索查询：%s

                    原始结果：
                    %s

                    压缩笔记：
                    """.formatted(toolName, truncate(query, 80), truncate(rawResult, 3000));

            String response = fastModel.chat(
                            ChatRequest.builder()
                                    .messages(List.of(UserMessage.from(prompt)))
                                    .build())
                    .aiMessage().text();

            String compressed = response.strip();
            long elapsed = (System.nanoTime() - start) / 1_000_000;

            log.info("ObservationCompressor: {} q={}, {} chars → {} chars, 耗时={}ms",
                    toolName, truncate(query, 30),
                    rawResult.length(), compressed.length(), elapsed);

            return compressed;

        } catch (Exception e) {
            log.warn("ObservationCompressor 失败，返回原文: {}", e.getMessage());
            return rawResult;
        }
    }

    private static String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : (s != null ? s : "");
    }
}
