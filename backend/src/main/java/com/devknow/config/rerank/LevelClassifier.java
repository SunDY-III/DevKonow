package com.devknow.config.rerank;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.input.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * LLM 层级分类器。
 *
 * <p>根据用户问题判断属于哪个知识层级（L1~L5），
 * 用于 RAG 检索时进行层级 Filter + 置信度降级决策。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LevelClassifier {

    private final ChatLanguageModel chatModel;
    private final ObjectMapper objectMapper;

    private static final String PROMPT_TEMPLATE = """
            你是一个知识库分类器。请判断以下问题属于哪个知识层级，只返回 JSON。

            层级定义：
            - L1 (原则层)：团队共识、技术原则、价值观
            - L2 (架构层)：架构决策、ADR、系统边界、设计文档
            - L3 (规范层)：编码规范、接口约定、发布流程
            - L4 (实现层)：代码说明、接口细节、配置示例
            - L5 (经验层)：故障复盘、排障 SOP、踩坑记录

            示例：
            问题：整个系统的技术选型原则是什么
            输出：{"level": 1, "confidence": 0.9, "reason": "讨论技术选型原则，属于团队共识和价值观"}

            问题：订单模块的架构设计文档在哪
            输出：{"level": 2, "confidence": 0.85, "reason": "询问架构设计文档，属于 L2 架构层"}

            问题：API 接口的命名规范是什么
            输出：{"level": 3, "confidence": 0.9, "reason": "涉及编码规范和接口约定，属于 L3 规范层"}

            问题：createOrder 方法的实现逻辑是什么
            输出：{"level": 4, "confidence": 0.95, "reason": "询问具体方法的代码实现，属于 L4 实现层"}

            问题：上次线上故障的根因是什么
            输出：{"level": 5, "confidence": 0.85, "reason": "询问故障复盘，属于 L5 经验层"}

            问题：{{question}}

            输出格式：{"level": int, "confidence": float, "reason": string}
            注意：level 取值范围 1~5，confidence 取值范围 0.0~1.0。必须返回合法的 JSON，不要包含 markdown 代码块标记。
            """;

    /**
     * 对用户问题进行层级分类。
     *
     * @param question 用户问题
     * @return 分类结果，解析失败时返回 level=0, confidence=0
     */
    public LevelResult classify(String question) {
        if (question == null || question.isBlank()) {
            return new LevelResult(0, 0.0, "empty question");
        }

        try {
            String prompt = PromptTemplate.from(PROMPT_TEMPLATE)
                    .apply(Map.of("question", question))
                    .text();

            ChatRequest request = ChatRequest.builder()
                    .messages(UserMessage.from(prompt))
                    .build();
            String response = chatModel.chat(request).aiMessage().text();

            // 解析 JSON 响应
            LevelResult result = parseResponse(response);
            log.info("层级分类: q={}, level={}, confidence={}, reason={}",
                    truncate(question, 50), result.getLevel(),
                    String.format("%.2f", result.getConfidence()), result.getReason());
            return result;

        } catch (Exception e) {
            log.warn("层级分类失败，降级为全量搜索: {}", e.getMessage());
            return new LevelResult(0, 0.0, "classification failed: " + e.getMessage());
        }
    }

    private LevelResult parseResponse(String response) {
        try {
            // 尝试从 JSON 代码块中提取
            String json = response;
            if (json.contains("```json")) {
                json = json.substring(json.indexOf("```json") + 7);
                json = json.substring(0, json.indexOf("```"));
            } else if (json.contains("```")) {
                json = json.substring(json.indexOf("```") + 3);
                json = json.substring(0, json.indexOf("```"));
            }
            json = json.trim();

            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            int level = ((Number) map.getOrDefault("level", 0)).intValue();
            double confidence = ((Number) map.getOrDefault("confidence", 0.0)).doubleValue();
            String reason = (String) map.getOrDefault("reason", "");

            // 校验合法性
            if (level < 1 || level > 5) level = 0;
            if (confidence < 0 || confidence > 1) confidence = 0;

            return new LevelResult(level, confidence, reason);
        } catch (Exception e) {
            log.warn("层级分类响应解析失败: {}", e.getMessage());
            return new LevelResult(0, 0.0, "parse error");
        }
    }

    private String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
