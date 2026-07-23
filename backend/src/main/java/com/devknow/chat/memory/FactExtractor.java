package com.devknow.chat.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 长期记忆事实提取器 —— 从对话历史中抽取结构化原子事实。
 *
 * <p>在 {@code MemoryService.compress()} 过程中调用，将即将被压缩的
 * 早期对话中的关键信息提取为持久化事实，存入 {@link FactMemoryStore}。
 *
 * <p>使用 fastChatLanguageModel（轻量模型），分类/提取任务足够。
 * 提取失败不影响主流程（仅丢失该轮事实记忆）。
 */
@Slf4j
@Component
public class FactExtractor {

    private final ChatLanguageModel fastModel;
    private final ObjectMapper objectMapper;

    public FactExtractor(@Qualifier("fastChatLanguageModel") ChatLanguageModel fastModel,
                          ObjectMapper objectMapper) {
        this.fastModel = fastModel;
        this.objectMapper = objectMapper;
    }

    /**
     * 从对话消息中提取事实。
     *
     * @param messages 待提取的对话消息列表
     * @param conversationId 会话 ID
     * @return 提取的事实列表，提取失败时返回空列表
     */
    public List<MemoryFact> extract(List<ChatMessage> messages, String conversationId) {
        if (messages == null || messages.size() < 2) return List.of();

        try {
            // 构建对话文本（去掉 SystemMessage）
            String conversationText = messages.stream()
                    .filter(m -> !(m instanceof SystemMessage))
                    .map(m -> {
                        if (m instanceof UserMessage) return "用户: " + m.text();
                        if (m instanceof AiMessage) return "助手: " + m.text();
                        return m.type() + ": " + m.text();
                    })
                    .collect(Collectors.joining("\n"));

            if (conversationText.length() > 4000) {
                conversationText = conversationText.substring(0, 4000) + "\n...（截断）";
            }

            String prompt = """
                    你是一个对话事实提取器。从以下对话中提取所有关键的技术事实、决策、用户偏好。

                    规则：
                    - 每个事实应该是原子性的（一句话一个事实）
                    - 类别：DECISION（决策）、PREFERENCE（偏好）、REQUIREMENT（需求）、ARCHITECTURE（架构）、FACT（一般事实）
                    - 只提取明确陈述的事实，不要推断猜测
                    - 忽略问候语、寒暄
                    - 如果没有任何值得记录的事实，返回空数组

                    对话：
                    %s

                    返回 JSON 数组，格式：
                    [{"text": "用户选用了 PostgreSQL", "category": "DECISION"}, ...]
                    只返回 JSON，不要 markdown 标记。
                    """.formatted(conversationText);

            String response = fastModel.chat(
                    ChatRequest.builder()
                            .messages(List.of(dev.langchain4j.data.message.UserMessage.from(prompt)))
                            .build())
                    .aiMessage().text();

            return parseResponse(response, conversationId);

        } catch (Exception e) {
            log.warn("Fact extraction failed for conversation {}: {}", conversationId, e.getMessage());
            return List.of();
        }
    }

    private List<MemoryFact> parseResponse(String response, String conversationId) {
        try {
            String json = response;
            if (json.contains("```json")) {
                json = json.substring(json.indexOf("```json") + 7);
                json = json.substring(0, json.indexOf("```"));
            } else if (json.contains("```")) {
                json = json.substring(json.indexOf("```") + 3);
                json = json.substring(0, json.indexOf("```"));
            }
            json = json.trim();

            List<Map<String, Object>> raw = objectMapper.readValue(
                    json, new TypeReference<List<Map<String, Object>>>() {});

            long now = System.currentTimeMillis();
            List<MemoryFact> facts = new ArrayList<>();
            for (Map<String, Object> entry : raw) {
                String text = (String) entry.get("text");
                String categoryStr = (String) entry.get("category");
                if (text == null || text.isBlank()) continue;

                FactCategory category = FactCategory.FACT; // default
                try {
                    category = FactCategory.valueOf(categoryStr.toUpperCase());
                } catch (Exception ignored) {}

                facts.add(MemoryFact.builder()
                        .id(UUID.randomUUID().toString())
                        .text(text.strip())
                        .category(category)
                        .conversationId(conversationId)
                        .createdAt(now)
                        .updatedAt(now)
                        .superseded(false)
                        .build());
            }

            log.info("FactExtractor: extracted {}/{} facts from {} messages",
                    facts.size(), raw.size(), conversationId);
            return facts;

        } catch (Exception e) {
            log.warn("Failed to parse fact extraction response: {}", e.getMessage());
            return List.of();
        }
    }
}
