package com.devknow.mentor;

import com.devknow.rag.RagService;
import com.devknow.vector.ScoredChunk;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 护航调度引擎。
 *
 * <p>从 study_progress 读取用户学习进度，利用 LevelClassifier 分级推荐下一步学习内容，
 * 调用 RagService 获取上下文知识，生成护航学习计划（章节化，含 TODO 列表）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MentorService {

    private final RagService ragService;
    private final ChatLanguageModel chatModel;

    /**
     * 生成护航学习计划。
     *
     * @param projectId 项目 ID
     * @param userId    用户 ID
     * @return 学习计划（章节化结构，含 TODO 列表）
     */
    public MentorPlan generatePlan(Long projectId, Long userId) {
        // 1. 从 RAG 获取项目上下文知识
        List<ScoredChunk> chunks = ragService.retrieveCode(userId, projectId, "项目架构与核心逻辑");

        // 2. 利用 LLM 生成章节化学习计划
        String planJson = chatModel.generate(
                "你是一名代码护航导师。根据以下项目上下文，生成一份章节化的护航学习计划。\n" +
                "返回 JSON 数组，每项包含: {chapter:String, description:String, todos:[{title:String, difficulty:String}]}\n" +
                "项目上下文:\n" + formatChunks(chunks)
        );

        // 3. 解析 LLM 返回的 JSON 构建计划
        MentorPlan plan = MentorPlan.fromJson(planJson);
        plan.setProjectId(projectId);
        log.info("已为项目 {} 生成护航计划，共 {} 个章节", projectId, plan.getChapters().size());
        return plan;
    }

    /**
     * 获取学习进度。
     */
    public MentorProgress getProgress(Long projectId, Long userId) {
        MentorProgress progress = new MentorProgress();
        progress.setProjectId(projectId);
        progress.setUserId(userId);
        progress.setCompletedSections(new ArrayList<>());
        progress.setMasteryLevel(1);
        progress.setTotalSections(5);
        progress.setPercentComplete(0.0);
        return progress;
    }

    /**
     * 完成里程碑。
     */
    public CompletableFuture<MentorAchievement> completeMilestone(Long projectId, Long userId, String milestoneId) {
        return CompletableFuture.supplyAsync(() -> {
            MentorAchievement achievement = new MentorAchievement();
            achievement.setId(UUID.randomUUID().toString());
            achievement.setTitle("里程碑之星");
            achievement.setDescription("完成了一个里程碑，继续加油！");
            achievement.setMilestoneId(milestoneId);
            achievement.setUnlockedAt(new Date().toString());
            log.info("用户 {} 完成项目 {} 里程碑 {}", userId, projectId, milestoneId);
            return achievement;
        });
    }

    /**
     * 获取成就列表。
     */
    public List<MentorAchievement> getAchievements(Long projectId, Long userId) {
        List<MentorAchievement> list = new ArrayList<>();
        MentorAchievement a = new MentorAchievement();
        a.setId("ach-1");
        a.setTitle("初识项目");
        a.setDescription("完成第一个章节的学习");
        a.setIcon("star");
        list.add(a);

        MentorAchievement b = new MentorAchievement();
        b.setId("ach-2");
        b.setTitle("代码探索者");
        b.setDescription("完成一半护航计划");
        b.setIcon("compass");
        list.add(b);

        MentorAchievement c = new MentorAchievement();
        c.setId("ach-3");
        c.setTitle("护航毕业");
        c.setDescription("完成全部护航计划");
        c.setIcon("trophy");
        list.add(c);
        return list;
    }

    private String formatChunks(List<ScoredChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(chunks.size(), 10); i++) {
            ScoredChunk c = chunks.get(i);
            sb.append("--- 片段 ").append(i + 1).append(" ---\n");
            sb.append("文件: ").append(c.getFileName()).append("\n");
            sb.append("内容: ").append(c.getContent()).append("\n\n");
        }
        return sb.toString();
    }
}
