package com.devknow.discover;

import com.devknow.governance.TokenAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.structured.Description;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 学习意图解析器。
 * 将用户的自然语言描述（如"我想学微服务网关"）解析为结构化的搜索意图，
 * 包含技术栈、框架、领域、难度等维度。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearnIntentParser {

    private final ChatLanguageModel chatModel;
    private final TokenAuditService tokenAuditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解析用户的学习意图。
     *
     * @param userId   用户ID
     * @param userInput 用户自然语言输入，如"想学 Spring Cloud 微服务"
     * @return 解析后的学习意图
     */
    public LearnIntent parse(Long userId, String userInput) {
        long start = System.currentTimeMillis();
        try {
            String prompt = """
                    你是一个学习意图分析器。用户想通过 GitHub 开源项目学习技术。
                    请将用户的需求解析为结构化的搜索意图，返回 JSON 格式（不要 markdown 标记）：

                    {
                      "technologies": ["技术栈关键词，如 Spring Cloud"],
                      "frameworks": ["框架名，如 Spring Boot"],
                      "domains": ["领域，如 微服务/网关/认证"],
                      "difficulty": "beginner|intermediate|advanced",
                      "searchKeywords": ["用于 GitHub 搜索的关键词列表，每个关键词 2-5 个词"],
                      "summary": "一句话总结用户想学什么"
                    }

                    用户输入：%s
                    """.formatted(userInput);

            String json = chatModel.generate(prompt);
            tokenAuditService.record(userId, "LEARN_INTENT_PARSE",
                    userInput.length() / 2, json.length() / 2);

            return parseJson(json, userInput);

        } catch (Exception e) {
            log.warn("LLM 解析学习意图失败，使用降级方案: input={}", userInput, e);
            return fallback(userInput);
        } finally {
            log.info("学习意图解析耗时: {} ms", System.currentTimeMillis() - start);
        }
    }

    private LearnIntent parseJson(String json, String rawInput) {
        try {
            // 清理可能的 markdown 标记
            json = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            JsonNode root = objectMapper.readTree(json);

            LearnIntent intent = new LearnIntent();
            intent.setRawInput(rawInput);

            if (root.has("technologies") && root.get("technologies").isArray()) {
                intent.setTechnologies(objectMapper.convertValue(root.get("technologies"), List.class));
            }
            if (root.has("frameworks") && root.get("frameworks").isArray()) {
                intent.setFrameworks(objectMapper.convertValue(root.get("frameworks"), List.class));
            }
            if (root.has("domains") && root.get("domains").isArray()) {
                intent.setDomains(objectMapper.convertValue(root.get("domains"), List.class));
            }
            if (root.has("difficulty")) {
                intent.setDifficulty(root.get("difficulty").asText("intermediate"));
            }
            if (root.has("searchKeywords") && root.get("searchKeywords").isArray()) {
                intent.setSearchKeywords(objectMapper.convertValue(root.get("searchKeywords"), List.class));
            }
            if (root.has("summary")) {
                intent.setSummary(root.get("summary").asText());
            }

            // 确保至少有一个搜索关键词
            if (intent.getSearchKeywords() == null || intent.getSearchKeywords().isEmpty()) {
                intent.setSearchKeywords(List.of(rawInput));
            }

            return intent;

        } catch (JsonProcessingException e) {
            log.warn("JSON 解析失败，使用降级: {}", json, e);
            return fallback(rawInput);
        }
    }

    private LearnIntent fallback(String rawInput) {
        LearnIntent intent = new LearnIntent();
        intent.setRawInput(rawInput);
        intent.setSearchKeywords(List.of(rawInput));
        intent.setDifficulty("intermediate");
        return intent;
    }

    @Data
    public static class LearnIntent {
        private String rawInput;
        private List<String> technologies;
        private List<String> frameworks;
        private List<String> domains;
        private String difficulty = "intermediate";
        private List<String> searchKeywords;
        private String summary;
    }
}
