package com.devknow.rag;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * HyDE (Hypothetical Document Embeddings) 假设文档嵌入生成器。
 *
 * <p>原理：用 LLM 先根据用户问题生成一段"假设性的回答/文档片段"，
 * 然后用这个假设文档的向量去检索知识库，而非直接用查询向量。
 * 因为假设文档的语义空间与知识库文档更接近，可以弥补查询-文档的表示鸿沟。
 *
 * <p>参考论文：Precise Zero-Shot Dense Retrieval without Relevance Labels (Gao et al., 2022)
 *
 * <p>使用 fastChatLanguageModel（轻量模型），HyDE 对生成质量要求不高，
 * 只需生成风格相似的假设文档，轻量模型足以胜任且延迟更低。
 */
@Slf4j
@Component
public class HydeGenerator {

    private final dev.langchain4j.model.chat.ChatLanguageModel chatModel;

    public HydeGenerator(
            @org.springframework.beans.factory.annotation.Qualifier("fastChatLanguageModel")
            dev.langchain4j.model.chat.ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 根据用户问题生成假设文档文本。
     *
     * @param question 用户原始问题
     * @return 假设文档文本（用于 embedding 检索）
     */
    public String generateHypothesis(String question) {
        if (question == null || question.isBlank()) return question;

        try {
            String prompt = """
                    你是一个技术文档撰写者。请针对以下问题，写一段**技术文档风格**的段落作为检索查询。
                    这段文本将被用于向量检索，请写出知识库中可能存在的文档内容的风格和用语。

                    要求：
                    - 用中文技术文档的语气
                    - 包含具体的技术术语、方法名、类名（如果问题中提到了）
                    - 不要评价问题本身，直接写"文档内容"

                    问题：%s

                    检索用文档：""".formatted(question);

            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(UserMessage.from(prompt)))
                    .build();
            String hypothesis = chatModel.chat(request)
                    .aiMessage().text();

            if (hypothesis == null || hypothesis.isBlank()) return question;

            log.debug("HyDE: q={}, hypothesis={}", truncate(question, 40), truncate(hypothesis, 80));
            return hypothesis;

        } catch (Exception e) {
            log.warn("HyDE 生成失败，回退到原始查询: {}", e.getMessage());
            return question;
        }
    }

    private String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
