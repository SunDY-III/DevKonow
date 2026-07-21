package com.devknow.feynman;

import com.devknow.common.UserContext;
import com.devknow.governance.TokenAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Feynman 检验服务。
 *
 * <p>在 LLM 回答后追加理解检验：LLM 生成追问 → 用户回答 → LLM 评判 → 通过/降级。
 * 使用独立 SSE 会话，不污染通用 Chat 流。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeynmanService {

    private final ChatLanguageModel chatModel;
    private final StringRedisTemplate redis;
    private final TokenAuditService tokenAuditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Duration SESSION_TTL = Duration.ofHours(2);
    private static final int MAX_ROUNDS = 3;
    private static final int PASS_THRESHOLD = 2; // 3 轮答对 2 轮即通过

    /**
     * 基于原始 LLM 回答生成 Feynman 追问。
     *
     * @param userId 用户 ID（由调用方传入，避免异步 ThreadLocal 丢失）
     */
    public String generateVerifyQuestion(Long userId, String conversationId, String originalQuestion,
                                          String originalAnswer, List<String> sourceChunks) {
        String prompt = """
                你是一名编程导师。你的学生刚刚学习了以下内容：

                学生问题：%s
                你的回答：%s

                现在请你生成一个 Feynman 理解检验问题，验证学生是否真正理解了你刚才讲的核心概念。
                要求：
                1. 问题应要求学生"用自己的话解释"一个核心概念，而非复述答案
                2. 聚焦回答中最关键的技术概念/设计决策
                3. 问题应简短直接（20 字以内），一次只问一个概念
                4. 如果学生回答中包含多个概念，优先问最容易产生误解的那个
                5. 只输出问题本身，不要附加任何说明

                Feynman 问题：
                """.formatted(originalQuestion, originalAnswer);

        String question = chatModel.generate(prompt);
        tokenAuditService.record(userId, "FEYNMAN_GENERATE",
                originalAnswer.length() / 2, question.length() / 2);
        return question;
    }

    /**
     * 评判用户的 Feynman 回答。
     */
    public FeynmanJudgment judge(String conversationId, String verifyQuestion,
                                  String userAnswer, String originalAnswer,
                                  int round, int correctSoFar) {
        long userId = UserContext.require();
        String prompt = """
                你是一名严格的编程导师。你问了一个理解检验问题，学生给出了回答。

                检验问题：%s
                学生回答：%s
                原始教学内容（供参考）：%s

                请评判学生的回答。输出 JSON（不要 markdown 标记）：
                {
                  "correct": true/false,
                  "feedback": "对学生的简短反馈（20 字以内）",
                  "hint": "如果不正确，给一个渐进式提示；如果正确则为空",
                  "gapAnalysis": "如果正确则为空；如果不正确，指出理解漏洞（30 字以内）"
                }

                评判标准：
                - 学生用自己的话解释，而非复述原文 → 正确
                - 学生理解了概念间的关系 → 正确
                - 学生只是复述了关键词但没有真正理解 → 不正确
                - 学生遗漏了核心要点 → 不正确
                """.formatted(verifyQuestion, userAnswer, originalAnswer);

        String json = chatModel.generate(prompt);
        tokenAuditService.record(userId, "FEYNMAN_JUDGE",
                userAnswer.length() / 2, json.length() / 2);

        try {
            String clean = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            return objectMapper.readValue(clean, FeynmanJudgment.class);
        } catch (JsonProcessingException e) {
            log.warn("Feynman 评判 JSON 解析失败: {}, 降级为不通过", json, e);
            FeynmanJudgment fallback = new FeynmanJudgment();
            fallback.setCorrect(false);
            fallback.setFeedback("评判解析异常，请重试");
            fallback.setHint("系统暂时无法评判你的回答，请稍后重试");
            return fallback;
        }
    }

    /**
     * 判断是否通过 Feynman 检验。
     */
    public boolean isPassed(int correctCount, int totalRounds) {
        return correctCount >= PASS_THRESHOLD || totalRounds >= MAX_ROUNDS;
    }

    /**
     * 保存 Feynman 会话到 Redis。
     */
    public void saveSession(FeynmanSession session) {
        try {
            redis.opsForValue().set("feynman:" + session.getConversationId(),
                    objectMapper.writeValueAsString(session), SESSION_TTL);
        } catch (JsonProcessingException e) {
            log.error("Feynman 会话序列化失败", e);
        }
    }

    /**
     * 从 Redis 加载 Feynman 会话。
     */
    public Optional<FeynmanSession> loadSession(String conversationId) {
        String json = redis.opsForValue().get("feynman:" + conversationId);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, FeynmanSession.class));
        } catch (JsonProcessingException e) {
            log.error("Feynman 会话反序列化失败", e);
            return Optional.empty();
        }
    }

    public static class FeynmanJudgment {
        private boolean correct;
        private String feedback;
        private String hint;
        private String gapAnalysis;

        public boolean isCorrect() { return correct; }
        public void setCorrect(boolean correct) { this.correct = correct; }
        public String getFeedback() { return feedback; }
        public void setFeedback(String feedback) { this.feedback = feedback; }
        public String getHint() { return hint; }
        public void setHint(String hint) { this.hint = hint; }
        public String getGapAnalysis() { return gapAnalysis; }
        public void setGapAnalysis(String gapAnalysis) { this.gapAnalysis = gapAnalysis; }
    }
}
