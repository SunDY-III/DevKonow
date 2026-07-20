package com.devknow.discover;

import com.devknow.discover.GitHubSearchService.GitHubRepo;
import com.devknow.discover.LearnIntentParser.LearnIntent;
import com.devknow.project.CodeProject;
import com.devknow.project.CodeProjectRepository;
import com.devknow.project.ProjectImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 发现编排服务。
 *
 * <p>协调学习意图解析 → GitHub 搜索 → 项目评分 → 推荐展示的完整流程，
 * 以及"只读导入"学习项目的能力。
 *
 * 参考 Clew Quest 的"4 问答式发现"模式。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscoverService {

    private final LearnIntentParser learnIntentParser;
    private final GitHubSearchService gitHubSearchService;
    private final ProjectScorer projectScorer;
    private final ProjectImportService projectImportService;
    private final CodeProjectRepository projectRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 完整发现流程：解析意图 → 搜索 → 评分 → 返回推荐。
     *
     * @param userId   用户 ID
     * @param userInput 用户输入，如"想学 Spring Cloud 微服务网关"
     * @return 推荐结果
     */
    public DiscoverResult discover(Long userId, String userInput) {
        long start = System.currentTimeMillis();

        // 1. 解析学习意图
        LearnIntent intent = learnIntentParser.parse(userId, userInput);
        log.info("学习意图解析结果: {}", intent);

        // 2. 搜索 GitHub
        List<GitHubRepo> repos = gitHubSearchService.searchRepos(
                intent.getSearchKeywords(),
                null,   // 不限制语言
                "stars",
                20      // 取前 20 个
        );

        // 3. 评分排序
        List<GitHubRepo> ranked = projectScorer.scoreAndRank(repos, intent);

        // 4. 截取前 8 个推荐
        List<GitHubRepo> topRecommendations = ranked.size() > 8
                ? ranked.subList(0, 8) : ranked;

        log.info("发现流程完成: 共 {} 个结果, 推荐 {} 个, 耗时 {} ms",
                repos.size(), topRecommendations.size(),
                System.currentTimeMillis() - start);

        DiscoverResult result = new DiscoverResult();
        result.setIntent(intent);
        result.setRecommendations(topRecommendations);
        result.setTotalFound(repos.size());
        return result;
    }

    /**
     * 以只读模式导入学习项目。
     * 只建索引不写业务数据，不关联用户项目列表。
     */
    public SseEmitter importLearningProject(String repoUrl) {
        SseEmitter emitter = new SseEmitter(300_000L);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));

        try {
            // 检查是否已导入为学习项目
            String repoName = extractRepoName(repoUrl);
            if (repoName == null) {
                notifyProgress(emitter, "error", "仓库地址格式错误", 0);
                emitter.complete();
                return emitter;
            }

            List<CodeProject> existing = projectRepository.findByStatus("LEARNING");
            for (CodeProject p : existing) {
                if (p.getRepoUrls() != null && p.getRepoUrls().contains(repoUrl)) {
                    notifyProgress(emitter, "exists", "该项目已作为学习项目导入", 100);
                    emitter.complete();
                    return emitter;
                }
            }

            // 创建只读学习项目记录
            sendProgress(emitter, closed, "creating", "正在准备学习项目...", 10);

            // 先验证 URL 格式，防止创建无效记录
            try {
                ProjectImportService.validateRepoUrl(repoUrl);
            } catch (Exception e) {
                notifyProgress(emitter, "error", "仓库地址无效: " + e.getMessage(), 0);
                emitter.complete();
                return emitter;
            }

            CodeProject project = CodeProject.builder()
                    .name(repoName).displayName(repoName)
                    .repoUrls(toJson(List.of(repoUrl)))
                    .status("LEARNING")
                    .totalFiles(0).totalMethods(0)
                    .build();
            project = projectRepository.save(project);
            final Long projectId = project.getId();

            sendProgress(emitter, closed, "cloning", "正在获取项目代码...", 20);

            // 委托导入流程
            try {
                projectImportService.importFromRepo(
                        repoUrl, false, null, false, emitter);
            } catch (Exception e) {
                log.warn("学习项目导入异常, 清理孤儿记录: projectId={}", projectId, e);
                projectRepository.deleteById(projectId);
                notifyProgress(emitter, "error", "导入异常: " + e.getMessage(), 0);
            }

        } catch (Exception e) {
            log.error("学习项目导入失败: repoUrl={}", repoUrl, e);
            notifyProgress(emitter, "error", "导入失败: " + e.getMessage(), 0);
        } finally {
            try { emitter.complete(); } catch (Exception ignored) {}
        }

        return emitter;
    }

    /**
     * 从仓库 URL 中提取仓库名。
     */
    private String extractRepoName(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) return null;
        String name = repoUrl;
        if (name.endsWith(".git")) name = name.substring(0, name.length() - 4);
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) name = name.substring(lastSlash + 1);
        return name.isEmpty() ? null : name;
    }

    // ======================== 辅助方法 ========================

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return "[]"; }
    }

    // ======================== SSE 辅助 ========================

    private void sendProgress(SseEmitter emitter, AtomicBoolean closed,
                              String stage, String message, int percent) {
        if (closed.get()) return;
        notifyProgress(emitter, stage, message, percent);
    }

    private void notifyProgress(SseEmitter emitter, String stage,
                                 String message, int percent) {
        try {
            emitter.send(SseEmitter.event().name("progress").data(
                    new DiscoverProgress(stage, message, percent)));
        } catch (Exception ignored) {}
    }

    // ======================== 数据模型 ========================

    public record DiscoverProgress(String stage, String message, int percent) {}

    @lombok.Data
    public static class DiscoverResult {
        private LearnIntent intent;
        private List<GitHubRepo> recommendations;
        private int totalFound;
    }
}
