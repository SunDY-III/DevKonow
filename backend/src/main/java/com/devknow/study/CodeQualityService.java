package com.devknow.study;

import com.devknow.common.UserContext;
import com.devknow.governance.TokenAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码质量评分服务。
 *
 * <p>对用户代码进行四维评分：可读性、健壮性、性能、测试性。
 * 支持改后重评和分数变化对比。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeQualityService {

    private final ChatLanguageModel chatModel;
    private final TokenAuditService tokenAuditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 对代码进行质量评分。
     *
     * @param code        代码内容
     * @param language    代码语言
     * @param context     上下文（文件路径等，可选）
     * @return 评分结果
     */
    public QualityReport score(String code, String language, String context) {
        long userId = UserContext.require();

        // 大文件截取前 200 行
        String[] lines = code.split("\n");
        String codeForAnalysis = lines.length > 200
                ? String.join("\n", java.util.Arrays.copyOf(lines, 200))
                + "\n...（文件过长，仅展示前 200 行）"
                : code;

        String prompt = """
                你是一名代码审查专家。请对以下 %s 代码进行四维评分。

                %s

                输出 JSON（不要 markdown 标记）：
                {
                  "readability": { "score": 1-10, "comment": "评语", "lineRefs": ["行号引用"] },
                  "robustness": { "score": 1-10, "comment": "评语", "lineRefs": ["行号引用"] },
                  "performance": { "score": 1-10, "comment": "评语", "lineRefs": ["行号引用"] },
                  "testability": { "score": 1-10, "comment": "评语", "lineRefs": ["行号引用"] },
                  "improvements": [
                    { "title": "改进项", "lineRef": "行号", "impact": "high|medium|low",
                      "suggestion": "具体改进建议", "expectedGain": "预期提升" }
                  ],
                  "summary": "总体评价"
                }

                评分标准：
                - 可读性：命名规范、注释质量、代码结构清晰度
                - 健壮性：边界处理、错误处理、防御式编程
                - 性能：算法复杂度、资源使用、潜在瓶颈
                - 测试性：可测试设计、依赖注入、关注点分离
                """.formatted(language != null ? language : "未知", codeForAnalysis);

        String json = chatModel.generate(prompt);
        tokenAuditService.record(userId, "CODE_QUALITY",
                codeForAnalysis.length() / 2, json.length() / 2);

        try {
            String clean = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            QualityReport report = objectMapper.readValue(clean, QualityReport.class);
            report.setOriginalCode(code);
            return report;
        } catch (JsonProcessingException e) {
            log.warn("代码评分 JSON 解析失败", e);
            return fallbackReport();
        }
    }

    private QualityReport fallbackReport() {
        QualityReport r = new QualityReport();
        r.setSummary("评分生成失败，请重试");
        return r;
    }

    // ======================== 数据模型 ========================

    @Data
    public static class QualityReport {
        private DimensionScore readability = new DimensionScore();
        private DimensionScore robustness = new DimensionScore();
        private DimensionScore performance = new DimensionScore();
        private DimensionScore testability = new DimensionScore();
        private List<ImprovementItem> improvements = new ArrayList<>();
        private String summary = "";
        private String originalCode;

        public double getAverageScore() {
            return (readability.score + robustness.score + performance.score + testability.score) / 4.0;
        }
    }

    @Data
    public static class DimensionScore {
        private int score = 5;
        private String comment = "";
        private List<String> lineRefs = new ArrayList<>();
    }

    @Data
    public static class ImprovementItem {
        private String title;
        private String lineRef;
        private String impact;
        private String suggestion;
        private String expectedGain;
    }
}
