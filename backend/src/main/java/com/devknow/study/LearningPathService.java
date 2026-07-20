package com.devknow.study;

import com.devknow.config.rerank.LevelClassifier;
import com.devknow.config.rerank.LevelResult;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 学习路径规划服务。
 *
 * <p>复用 LevelClassifier 对问题进行层级分类，
 * 结合项目研读结果生成 L1~L5 学习路线图。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningPathService {

    private final LevelClassifier levelClassifier;

    /** L1~L5 层级名称 */
    private static final Map<Integer, String> LEVEL_NAMES = Map.of(
            1, "原则层 - 团队共识与技术原则",
            2, "架构层 - 系统架构与设计决策",
            3, "规范层 - 编码规范与接口约定",
            4, "实现层 - 代码实现细节",
            5, "经验层 - 故障复盘与最佳实践"
    );

    /** L1~L5 学习建议 */
    private static final Map<Integer, String> LEVEL_ADVICE = Map.of(
            1, "了解项目的整体技术选型、架构原则和设计理念",
            2, "深入理解模块划分、系统边界和关键设计决策",
            3, "掌握编码规范、API 约定和开发流程",
            4, "阅读核心方法的实现，理解业务逻辑",
            5, "查看历史故障和分析，积累实战经验"
    );

    /**
     * 对问题进行层级分类。
     *
     * @param question 用户问题
     * @return 层级分类结果
     */
    public LevelResult classifyQuestion(String question) {
        return levelClassifier.classify(question);
    }

    /**
     * 获取指定层级的学习内容建议。
     *
     * @param level 层级 1~5
     * @return 学习阶段信息
     */
    public StudyLevel getLevelInfo(int level) {
        return StudyLevel.builder()
                .level(level)
                .name(LEVEL_NAMES.getOrDefault(level, "未知层级"))
                .advice(LEVEL_ADVICE.getOrDefault(level, ""))
                .build();
    }

    /**
     * 生成完整的 L1~L5 学习路线图。
     */
    public List<StudyLevel> getRoadmap() {
        return List.of(
                getLevelInfo(1),
                getLevelInfo(2),
                getLevelInfo(3),
                getLevelInfo(4),
                getLevelInfo(5)
        );
    }

    @Data
    @Builder
    public static class StudyLevel {
        private int level;
        private String name;
        private String advice;
    }
}
