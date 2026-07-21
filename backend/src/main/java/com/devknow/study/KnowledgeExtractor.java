package com.devknow.study;

import com.devknow.common.UserContext;
import com.devknow.governance.TokenAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 代码知识提取服务。
 *
 * <p>在 AI 辅助后，自动从问答对 + 代码上下文中提炼结构化知识点笔记。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeExtractor {

    private final ChatLanguageModel chatModel;
    private final KnowledgePointRepository knowledgePointRepository;
    private final TokenAuditService tokenAuditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 从问答对话中提取知识点。
     *
     * @param userId         用户 ID
     * @param question       用户问题
     * @param answer         LLM 回答
     * @param codeContext    相关代码上下文（可选）
     * @param projectId      关联项目 ID（可选）
     * @return 提取的知识点
     */
    public KnowledgePoint extract(Long userId, String question, String answer,
                                   String codeContext, Long projectId) {
        String prompt = """
                你是一名技术知识整理专家。请从以下问答对话中提取一个核心知识点，输出 JSON（不要 markdown 标记）。

                {
                  "title": "知识点标题（15 字以内）",
                  "concept": "核心概念解释（50-150 字，用通俗语言）",
                  "difficultyLevel": 1-5 的整数（L1 最基础 L5 最深入）,
                  "patternName": "关联的设计模式或技术模式（如无则为空）",
                  "prerequisiteTitles": ["前置知识点标题列表（如无可为空）"]
                }

                用户问题：%s
                AI 回答：%s
                %s
                """.formatted(question, answer,
                codeContext != null ? "相关代码上下文：\n" + codeContext : "");

        String json = chatModel.generate(prompt);
        tokenAuditService.record(userId, "KNOWLEDGE_EXTRACT",
                answer.length() / 2, json.length() / 2);

        try {
            String clean = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            var node = objectMapper.readTree(clean);

            KnowledgePoint kp = KnowledgePoint.builder()
                    .title(node.has("title") ? node.get("title").asText() : "知识点")
                    .concept(node.has("concept") ? node.get("concept").asText() : "")
                    .difficultyLevel(node.has("difficultyLevel") ? node.get("difficultyLevel").asInt(3) : 3)
                    .patternName(node.has("patternName") ? node.get("patternName").asText() : null)
                    .sourceQuestion(question)
                    .relatedProjectId(projectId)
                    .feynmanPassCount(0)
                    .reviewCount(0)
                    .build();

            return knowledgePointRepository.save(kp);

        } catch (JsonProcessingException e) {
            log.warn("知识提取 JSON 解析失败", e);
            String safeAnswer = answer != null ? answer : "";
            String truncatedConcept = safeAnswer.length() > 200 ? safeAnswer.substring(0, 200) + "..." : safeAnswer;
            KnowledgePoint kp = KnowledgePoint.builder()
                    .title("知识点")
                    .concept(truncatedConcept)
                    .difficultyLevel(3)
                    .sourceQuestion(question)
                    .relatedProjectId(projectId)
                    .build();
            return knowledgePointRepository.save(kp);
        }
    }

    /**
     * 获取项目的所有知识点。
     */
    public java.util.List<KnowledgePoint> getProjectKnowledgePoints(Long projectId) {
        return knowledgePointRepository.findByRelatedProjectIdOrderByCreatedAtDesc(projectId);
    }

    /**
     * 获取知识点统计。
     */
    public java.util.Map<String, Object> getStats(Long projectId) {
        long count = knowledgePointRepository.countByRelatedProjectId(projectId);
        return java.util.Map.of("totalKnowledgePoints", count);
    }
}
