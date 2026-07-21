package com.devknow.study;

import com.devknow.common.UserContext;
import com.devknow.governance.TokenAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 面试演练服务。
 *
 * <p>基于研读项目的代码分析结果，生成技术面试题，LLM 模拟面试官进行追问。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewService {

    private final ChatLanguageModel chatModel;
    private final TokenAuditService tokenAuditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 基于项目信息生成面试题。
     *
     * @param projectId     项目 ID
     * @param projectName   项目名称
     * @param architecture  架构描述（可选）
     * @param patterns      识别到的设计模式（可选）
     * @param style         面试风格：gentle / strict / english
     * @return 面试题列表
     */
    public List<InterviewQuestion> generateQuestions(Long projectId, String projectName,
                                                      String architecture, List<String> patterns,
                                                      String style) {
        long userId = UserContext.require();
        String patternContext = (patterns != null && !patterns.isEmpty())
                ? "项目中使用的设计模式：" + String.join(", ", patterns) : "";
        String archContext = architecture != null ? "项目架构：" + architecture : "";

        String prompt = """
                你是一名技术面试官。基于以下项目信息，生成 5 道技术面试题。
                题目应覆盖：架构设计思路、技术选型考量、代码实现细节、故障场景应对 四类。

                项目名称：%s
                %s
                %s

                面试风格：%s

                输出 JSON 数组（不要 markdown 标记）：
                [
                  {
                    "question": "面试题",
                    "category": "architecture|technology|implementation|troubleshooting",
                    "difficulty": "easy|medium|hard",
                    "expectedAnswer": "参考答案要点",
                    "followUpCount": 2
                  }
                ]
                """.formatted(projectName, archContext, patternContext, style);

        String json = chatModel.generate(prompt);
        tokenAuditService.record(userId, "INTERVIEW_GENERATE",
                prompt.length() / 2, json.length() / 2);

        try {
            String clean = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            List<InterviewQuestion> questions = objectMapper.readValue(clean,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, InterviewQuestion.class));
            return questions != null ? questions : new ArrayList<>();
        } catch (JsonProcessingException e) {
            log.warn("面试题 JSON 解析失败", e);
            return List.of(new InterviewQuestion("请介绍一下该项目的整体架构设计", "architecture", "medium", "", 2));
        }
    }

    /**
     * 生成追问（基于用户上一轮回答）。
     */
    public String generateFollowUp(Long userId, String question, String userAnswer,
                                    String expectedAnswer, int depth, String style) {
        String prompt = """
                你是一名技术面试官。你问了候选人一个问题。

                问题：%s
                候选人的回答：%s
                参考答案要点：%s
                当前追问深度：%d

                基于候选人的回答，生成一个合理的追问。
                要求：
                - 如果候选人回答正确且深入，追问应进一步深挖原理
                - 如果候选人回答泛泛，追问应要求具体方案和 trade-off
                - 如果候选人回答错误，追问应引导纠偏
                - 一次只问一个问题，不超过 20 字
                - 面试风格：%s

                只输出追问问题本身：
                """.formatted(question, userAnswer, expectedAnswer, depth, style);

        String followUp = chatModel.generate(prompt);
        tokenAuditService.record(userId, "INTERVIEW_FOLLOWUP",
                userAnswer.length() / 2, followUp.length() / 2);
        return followUp;
    }

    /**
     * 生成面试反馈（雷达图评分）。
     */
    public InterviewFeedback generateFeedback(Long userId, String question, String userAnswer,
                                               String expectedAnswer, List<String> followUpQAs) {
        String qaHistory = "追问记录：\n" + String.join("\n", followUpQAs);

        String prompt = """
                请评估候选人对以下面试问题的回答表现，输出 JSON（不要 markdown 标记）：

                {
                  "depthScore": 1-10,
                  "completenessScore": 1-10,
                  "clarityScore": 1-10,
                  "depthComment": "技术深度评语",
                  "completenessComment": "完整性评语",
                  "clarityComment": "表达清晰度评语",
                  "missedPoints": ["遗漏要点列表"],
                  "improvementSuggestions": ["改进建议列表"]
                }

                问题：%s
                候选人回答：%s
                参考答案：%s
                %s
                """.formatted(question, userAnswer, expectedAnswer, qaHistory);

        String json = chatModel.generate(prompt);
        tokenAuditService.record(userId, "INTERVIEW_FEEDBACK",
                userAnswer.length() / 2, json.length() / 2);

        try {
            String clean = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            return objectMapper.readValue(clean, InterviewFeedback.class);
        } catch (JsonProcessingException e) {
            log.warn("面试反馈 JSON 解析失败", e);
            return new InterviewFeedback();
        }
    }

    // ======================== 数据模型 ========================

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class InterviewQuestion {
        private String question;
        private String category;
        private String difficulty;
        private String expectedAnswer;
        private int followUpCount;
    }

    @lombok.Data
    public static class InterviewFeedback {
        private int depthScore = 5;
        private int completenessScore = 5;
        private int clarityScore = 5;
        private String depthComment = "";
        private String completenessComment = "";
        private String clarityComment = "";
        private List<String> missedPoints = new ArrayList<>();
        private List<String> improvementSuggestions = new ArrayList<>();
    }
}
