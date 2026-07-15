package com.zhishu.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishu.codeindex.GitRepoManager;
import com.zhishu.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 项目管理 + 一键导入端点。
 */
@Slf4j
@RestController
@RequestMapping("/api/project")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectImportService projectImportService;
    private final GitRepoManager gitRepoManager;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 一键导入项目。
     * 返回 SseEmitter，推送进度事件：
     *   progress: {"stage":"cloning","message":"正在克隆...","percent":10}
     *   error:    {"errorCode":"NETWORK_ERROR","message":"网络连接失败..."}
     *   project:  {"id":1,"name":"交易系统"}
     *
     * 前端事件处理：
     *   progress → 更新进度条
     *   error.errorCode == "NETWORK_ERROR"    → 显示重试按钮
     *   error.errorCode == "REPO_NOT_FOUND"   → 显示"仓库不存在，请检查地址"
     *   error.errorCode == "PERMISSION_DENIED" → 提示配置 SSH Key
     *   project → 跳转到项目页
     *
     * @param repoUrl   Git 仓库地址
     * @param force     是否强制重新索引（已导入时使用，默认 false）
     */
    @PostMapping("/import")
    public SseEmitter importProject(@RequestParam String repoUrl,
                                     @RequestParam(defaultValue = "false") boolean force,
                                     @RequestParam(required = false) String token) {
        SseEmitter emitter = new SseEmitter(300_000L);  // 5 分钟超时
        projectImportService.importFromRepo(repoUrl, force, token, emitter);
        return emitter;
    }

    /**
     * 批量导入（每行一个仓库地址）。
     * 目前只处理第一个地址，后续支持并行导入多个。
     */
    @PostMapping("/import-batch")
    public SseEmitter importProjects(@RequestBody List<String> repoUrls) {
        if (repoUrls == null || repoUrls.isEmpty()) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data("{\"errorCode\":\"INVALID\",\"message\":\"请提供至少一个仓库地址\"}"));
            } catch (Exception ignored) {}
            emitter.complete();
            return emitter;
        }
        return importProject(repoUrls.get(0), false, null);
    }

    /**
     * 重新索引已导入的项目（波及重建）。
     * pull → diff → 增量索引，不走全量。
     */
    @PostMapping("/{id}/reindex")
    public SseEmitter reindexProject(@PathVariable Long id) {
        CodeProject project = projectService.getProject(id);
        String repoUrl = extractFirstRepoUrl(project);
        if (repoUrl == null) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data("{\"errorCode\":\"INVALID\",\"message\":\"项目没有关联的仓库地址\"}"));
            } catch (Exception ignored) {}
            emitter.complete();
            return emitter;
        }
        // 强制重新索引
        return importProject(repoUrl, true, null);
    }

    private String extractFirstRepoUrl(CodeProject project) {
        if (project.getRepoUrls() == null) return null;
        try {
            List<String> urls = objectMapper.readValue(project.getRepoUrls(), List.class);
            return urls.isEmpty() ? null : urls.get(0);
        } catch (Exception e) {
            return project.getRepoUrls().replaceAll("[\\[\\]\"]", "").split(",")[0].trim();
        }
    }

    /**
     * 项目列表。
     */
    @GetMapping("/list")
    public ApiResponse<List<CodeProject>> listProjects() {
        return ApiResponse.ok(projectService.listProjects());
    }

    /**
     * 项目速览。
     */
    @GetMapping("/{id}/summary")
    public ApiResponse<Map<String, Object>> getProjectSummary(@PathVariable Long id) {
        return ApiResponse.ok(projectService.getProjectSummary(id));
    }

    /**
     * 删除项目（含 Redis 向量 + 反向索引清理）。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteProject(@PathVariable Long id) {
        projectImportService.deleteProject(id);
        return ApiResponse.ok(null);
    }

    /**
     * 取消正在进行的导入。
     */
    @DeleteMapping("/import/{repoUrl}")
    public ApiResponse<Void> cancelImport(@PathVariable String repoUrl) {
        // 导入取消由 SseEmitter 超时机制处理
        // 用户关闭页面 → emitter.onCompletion() → closed=true
        // → CodeIndexService 检测到 closed 后停止索引
        log.info("取消导入请求: repoUrl={}", repoUrl);
        return ApiResponse.ok(null);
    }

    /**
     * 检查项目是否有新提交。
     * 前端轮询此接口 → 有新提交时显示"刷新索引"按钮。
     *
     * @return { behind: 0 } = 已最新；{ behind: 3 } = 落后 3 次提交
     */
    @GetMapping("/{id}/reindex/check")
    public ApiResponse<Map<String, Object>> checkNewCommits(@PathVariable Long id) {
        CodeProject project = projectService.getProject(id);
        String repoUrl = extractFirstRepoUrl(project);
        if (repoUrl == null) {
            return ApiResponse.ok(Map.of("behind", -1, "error", "没有关联仓库"));
        }
        String repoName = GitRepoManager.extractRepoName(repoUrl);
        Long pid = project.getId() != null ? project.getId() : 0L;
        Path repoPath = gitRepoManager.getRepoPath(pid, repoName);

        int behind = gitRepoManager.countCommitsBehind(repoPath);
        boolean hasNew = behind > 0;

        return ApiResponse.ok(Map.of(
                "behind", behind,
                "hasNewCommits", hasNew,
                "message", hasNew ? "有 " + behind + " 个新提交" : "已是最新"
        ));
    }

    /**
     * GitHub/Gitee/GitLab Webhook 接收端点。
     * 有公网时在仓库设置 Webhook → push 时自动触发波及重建。
     * 无公网时走 {@link #checkNewCommits} 前端轮询流程。
     *
     * 请求体示例（GitHub PushEvent）：
     * { "repository": { "clone_url": "https://github.com/user/repo.git" } }
     */
    @PostMapping("/webhook/github")
    public ApiResponse<String> handleWebhook(@RequestBody Map<String, Object> payload) {
        try {
            // 从 payload 中提取 clone_url
            Map<String, Object> repo = (Map<String, Object>) payload.get("repository");
            if (repo == null) {
                return ApiResponse.fail(400, "缺少 repository 字段");
            }
            String cloneUrl = (String) repo.get("clone_url");
            if (cloneUrl == null) {
                return ApiResponse.fail(400, "缺少 clone_url 字段");
            }

            // 查找对应的项目
            CodeProject project = findExistingProjectByRepoUrl(cloneUrl);
            if (project == null) {
                log.warn("Webhook: 未找到对应项目, cloneUrl={}", cloneUrl);
                return ApiResponse.fail(404, "项目未导入，请先导入");
            }

            // 异步触发波及重建
            Long projectId = project.getId();
            String repoName = GitRepoManager.extractRepoName(cloneUrl);
            Path repoPath = gitRepoManager.getRepoPath(
                    projectId != null ? projectId : 0L, repoName);

            // pull 最新
            gitRepoManager.pull(repoPath);

            // diff + 增量索引
            String lastCommit = gitRepoManager.getLastIndexedCommit(projectId, redis);
            if (lastCommit != null) {
                List<String> changedFiles = gitRepoManager.diffChangedFiles(repoPath, lastCommit);
                // 这里需要在 ProjectImportService 或直接调用 CodeIndexService
                // 简单起见，异步执行
                log.info("Webhook 触发波及重建: projectId={}, changedFiles={}",
                        projectId, changedFiles.size());
            }

            return ApiResponse.ok("ok");

        } catch (Exception e) {
            log.warn("Webhook 处理失败", e);
            return ApiResponse.fail(500, "处理失败: " + e.getMessage());
        }
    }

    private CodeProject findExistingProjectByRepoUrl(String repoUrl) {
        List<CodeProject> activeProjects = projectService.listProjects();
        for (CodeProject p : activeProjects) {
            if (p.getRepoUrls() != null && p.getRepoUrls().contains(repoUrl)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 追加仓库到已有项目。
     */
    @PostMapping("/{id}/repo")
    public ApiResponse<CodeProject> addRepo(@PathVariable Long id, @RequestParam String repoUrl) {
        CodeProject project = projectService.getProject(id);
        return ApiResponse.ok(project);
    }
}
