package com.zhishu.project;

import com.zhishu.codeindex.GitRepoManager;
import com.zhishu.codeindex.CodeIndexService;
import com.zhishu.codeindex.GitHistoryIndexer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * 定时检查所有已导入项目的新提交。
 * Webhook 的兜底——没有公网环境时也能自动更新索引。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledCheckService {

    private final GitRepoManager gitRepoManager;
    private final CodeProjectRepository projectRepository;
    private final CodeIndexService codeIndexService;
    private final GitHistoryIndexer gitHistoryIndexer;
    private final StringRedisTemplate redis;

    @Scheduled(fixedRate = 300_000)  // 每 5 分钟
    public void checkAllProjects() {
        List<CodeProject> projects = projectRepository.findByStatus("ACTIVE");
        for (CodeProject project : projects) {
            try {
                checkProject(project);
            } catch (Exception e) {
                log.warn("定时检查失败: projectId={}", project.getId(), e);
            }
        }
    }

    private void checkProject(CodeProject project) {
        Long projectId = project.getId();
        String repoUrl = extractUrl(project);
        if (repoUrl == null) return;

        String repoName = GitRepoManager.extractRepoName(repoUrl);
        Path repoPath = gitRepoManager.getRepoPath(projectId != null ? projectId : 0L, repoName);

        if (!repoPath.toFile().exists()) return;

        // 检查是否有新提交
        int behind = gitRepoManager.countCommitsBehind(repoPath);
        if (behind <= 0) return;

        log.info("定时检查: projectId={} 落后 {} 次提交，自动重建", projectId, behind);

        // pull + diff + 波及重建
        gitRepoManager.pull(repoPath);
        String lastCommit = redis.opsForValue().get("index:commit:" + projectId);
        if (lastCommit != null) {
            List<String> changedFiles = gitRepoManager.diffChangedFiles(repoPath, lastCommit);
            if (changedFiles.isEmpty()) {
                String ts = redis.opsForValue().get("index:timestamp:" + projectId);
                if (ts != null) {
                    changedFiles = gitRepoManager.diffSinceTimestamp(repoPath, Long.parseLong(ts));
                }
            }
            codeIndexService.indexIncremental(projectId, repoName, repoPath, changedFiles);
            // 索引 Git 历史
            gitHistoryIndexer.indexCommits(projectId, repoName, repoPath);
        }
    }

    private String extractUrl(CodeProject project) {
        if (project.getRepoUrls() == null) return null;
        try {
            var urls = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(project.getRepoUrls(), List.class);
            return urls.isEmpty() ? null : urls.get(0).toString();
        } catch (Exception e) {
            return project.getRepoUrls().replaceAll("[\\[\\]\"]", "").split(",")[0].trim();
        }
    }
}
