package com.devknow.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Contextual Retrieval 描述生成器 —— 为每个 Chunk 生成上下文描述，提升检索精度。
 *
 * <p>Anthropic 2024 年提出的方法：将 Chunk 内容与一段简短上下文描述拼接后共同嵌入，
 * 使向量检索能感知 chunk 在文档中的角色和上下文，而非仅匹配字面关键词。
 *
 * <p>使用 batch 模式：将多个 Chunk 打包到一个 Prompt 中一次调用 LLM，
 * 减少 API 调用次数和 Token 开销。用 fastChatLanguageModel（轻量模型），
 * 分类/摘要任务小模型即可胜任。
 *
 * <p>参考：https://www.anthropic.com/news/contextual-retrieval
 */
@Slf4j
@Component
public class ContextualDescriptionGenerator {

    private final ChatLanguageModel fastModel;
    private final ObjectMapper objectMapper;

    @Value("${app.rag.contextual-description.enabled:true}")
    private boolean enabled;

    @Value("${app.rag.contextual-description.batch-size:10}")
    private int batchSize;

    public ContextualDescriptionGenerator(
            @Qualifier("fastChatLanguageModel") ChatLanguageModel fastModel,
            ObjectMapper objectMapper) {
        this.fastModel = fastModel;
        this.objectMapper = objectMapper;
    }

    /**
     * 为 Chunk 列表生成上下文描述。
     *
     * @param chunks   语义 Chunk（需已设置 heading/headingLevel）
     * @param documentTitle 文档标题/文件名
     * @return contextDescription 已填充的 Chunk 列表（修改原对象，也返回）
     */
    public List<SemanticChunk> enrich(List<SemanticChunk> chunks, String documentTitle) {
        if (!enabled || chunks == null || chunks.isEmpty()) {
            return chunks;
        }

        // 分批处理
        for (int batchStart = 0; batchStart < chunks.size(); batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, chunks.size());
            List<SemanticChunk> batch = chunks.subList(batchStart, batchEnd);

            try {
                List<String> descriptions = generateBatch(batch, documentTitle);
                for (int i = 0; i < descriptions.size(); i++) {
                    batch.get(i).setContextDescription(descriptions.get(i));
                }
            } catch (Exception e) {
                log.warn("Contextual description batch failed ({}~{}), 降级为标题描述",
                        batchStart, batchEnd, e);
                // 降级：用标题作为描述
                for (SemanticChunk chunk : batch) {
                    chunk.setContextDescription(buildFallbackDescription(chunk));
                }
            }
        }

        int described = (int) chunks.stream()
                .filter(c -> c.getContextDescription() != null)
                .count();
        log.info("Contextual description generated: {}/{} chunks", described, chunks.size());

        return chunks;
    }

    /**
     * 批量生成描述 —— 一次 Prompt 中给多个 Chunk，LLM 返回 JSON 数组。
     */
    private List<String> generateBatch(List<SemanticChunk> batch, String documentTitle) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个文档摘要助手。为以下文档片段的每一条生成一句简短的上下文描述。\n\n");
        prompt.append("文档标题：").append(documentTitle != null ? documentTitle : "").append("\n\n");

        prompt.append("请为以下每个片段生成描述（1~2句话），描述该片段在文档中的角色和核心内容。\n");
        prompt.append("描述应该简洁、具体，能独立帮助理解该片段的上下文。\n\n");

        for (int i = 0; i < batch.size(); i++) {
            SemanticChunk chunk = batch.get(i);
            prompt.append("--- 片段 ").append(i + 1).append(" ---\n");
            if (chunk.getHeading() != null && !chunk.getHeading().isBlank()) {
                prompt.append("所属标题：").append(chunk.getHeading()).append("\n");
            }
            // 截断内容避免超出上下文
            String content = chunk.getContent();
            if (content != null && content.length() > 800) {
                content = content.substring(0, 800) + "...（截断）";
            }
            prompt.append("内容：").append(content).append("\n\n");
        }

        prompt.append("请只返回 JSON 数组，格式：\n");
        prompt.append("[{\"index\": 1, \"description\": \"此片段描述了...\"}, ...]\n");
        prompt.append("不要包含 markdown 标记，只返回 JSON。");

        String response;
        try {
            response = fastModel.chat(
                            ChatRequest.builder()
                                    .messages(List.of(UserMessage.from(prompt.toString())))
                                    .build())
                    .aiMessage().text();
        } catch (Exception e) {
            log.warn("LLM description generation failed", e);
            throw e;
        }

        return parseBatchResponse(response, batch.size());
    }

    /**
     * 解析 LLM 批量返回的 JSON。
     */
    private List<String> parseBatchResponse(String response, int expectedSize) {
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

            List<Map<String, Object>> results = objectMapper.readValue(
                    json, new TypeReference<List<Map<String, Object>>>() {});

            Map<Integer, String> descriptionMap = new HashMap<>();
            for (Map<String, Object> entry : results) {
                int index = ((Number) entry.get("index")).intValue();
                String desc = (String) entry.get("description");
                if (desc != null && !desc.isBlank()) {
                    descriptionMap.put(index, desc.strip());
                }
            }

            List<String> descriptions = new ArrayList<>(expectedSize);
            for (int i = 0; i < expectedSize; i++) {
                descriptions.add(descriptionMap.getOrDefault(i + 1,
                        buildFallbackDescription(null)));
            }
            return descriptions;

        } catch (Exception e) {
            log.warn("Failed to parse batch description response: {}", e.getMessage());
            throw new RuntimeException("Parse failed", e);
        }
    }

    /**
     * 降级：用标题作为描述。
     */
    private String buildFallbackDescription(SemanticChunk chunk) {
        if (chunk == null) return "";
        if (chunk.getHeading() != null && !chunk.getHeading().isBlank()) {
            return "文档章节：" + chunk.getHeading();
        }
        // 取前 50 字作为描述
        String content = chunk.getContent();
        if (content != null && !content.isBlank()) {
            return content.substring(0, Math.min(50, content.length())) + "...";
        }
        return "";
    }
}
