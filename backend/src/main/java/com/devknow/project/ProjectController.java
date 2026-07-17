package com.devknow.project;

import com.devknow.codeindex.GitRepoManager;
import com.devknow.common.ApiResponse;
import com.devknow.common.BizException;
import com.devknow.common.GitException;
import com.devknow.common.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/project")
@RequiredArgsConstructor
public class ProjectController {

    private final GitRepoManager gitRepoManager;
    private final ProjectService projectService;
    private final ProjectImportService projectImportService;
    private final CodeProjectRepository projectRepository;
    private final RepoSizeChecker repoSizeChecker;

    @GetMapping("/list")
    public ApiResponse<List<CodeProject>> list() {
        return ApiResponse.ok(projectService.listProjects());
    }

    @GetMapping("/{id}/summary")
    public ApiResponse<Map<String, Object>> summary(@PathVariable Long id) {
        UserContext.require();
        CodeProject project = projectRepository.findById(id)
                .orElseThrow(() -> new BizException("项目不存在"));
        return ApiResponse.ok(Map.of(
                "id", project.getId(),
                "name", project.getName(),
                "displayName", project.getDisplayName(),
                "language", project.getLanguage(),
                "framework", project.getFramework(),
                "totalFiles", project.getTotalFiles() != null ? project.getTotalFiles() : 0,
                "totalMethods", project.getTotalMethods() != null ? project.getTotalMethods() : 0
        ));
    }

    @PostMapping("/verify")
    public ApiResponse<Map<String, Object>> verifyRepo(@RequestParam String repoUrl,
                                                        @RequestParam(required = false) String token,
                                                        @RequestParam(required = false) String sshKey) {
        try {
            boolean isSsh = sshKey != null && !sshKey.isBlank();
            String result = gitRepoManager.verifyRepo(repoUrl, isSsh ? sshKey : token, isSsh);
            String repoName = GitRepoManager.extractRepoName(repoUrl);
            String authType = isSsh ? "SSH Key" : "Token";

            RepoSizeChecker.RepoInfo sizeInfo = repoSizeChecker.check(repoUrl);

            Map<String, Object> resp = new java.util.LinkedHashMap<>();
            resp.put("valid", sizeInfo.exists());
            resp.put("repoName", repoName);
            resp.put("authType", authType);
            resp.put("detail", result);
            resp.put("sizeMb", sizeInfo.sizeMb());
            resp.put("estimatedFiles", sizeInfo.estimatedFiles());
            resp.put("sizeLevel", sizeInfo.level().name());
            resp.put("recommendMode", sizeInfo.recommendMode());
            resp.put("message", sizeInfo.message());

            return ApiResponse.ok(resp);
        } catch (GitException e) {
            return ApiResponse.fail(400, e.getErrorCode() + ": " + e.getMessage());
        }
    }

    @PostMapping("/import")
    public SseEmitter importProject(@RequestParam String repoUrl,
                                     @RequestParam(defaultValue = "false") boolean force,
                                     @RequestParam(required = false) String token,
                                     @RequestParam(required = false) String sshKey) {
        UserContext.require();

        if (!force) {
            RepoSizeChecker.RepoInfo sizeInfo = repoSizeChecker.check(repoUrl);
            if (sizeInfo.level() == RepoSizeChecker.SizeLevel.REJECT) {
                throw new BizException(sizeInfo.message());
            }
        }

        SseEmitter emitter = new SseEmitter(600_000L);
        boolean isSsh = sshKey != null && !sshKey.isBlank();
        projectImportService.importFromRepo(repoUrl, force, isSsh ? sshKey : token, isSsh, emitter);
        return emitter;
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        UserContext.require();
        projectService.deleteProject(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/reindex")
    public SseEmitter reindexProject(@PathVariable Long id) {
        UserContext.require();
        CodeProject project = projectRepository.findById(id)
                .orElseThrow(() -> new BizException("项目不存在"));
        String repoUrl = extractFirstRepoUrl(project);
        if (repoUrl == null) throw new BizException("项目无仓库地址");

        SseEmitter emitter = new SseEmitter(600_000L);
        projectImportService.importFromRepo(repoUrl, true, null, false, emitter);
        return emitter;
    }

    @GetMapping("/{id}/reindex/check")
    public ApiResponse<Map<String, Object>> checkNewCommits(@PathVariable Long id) {
        UserContext.require();
        CodeProject project = projectRepository.findById(id)
                .orElseThrow(() -> new BizException("项目不存在"));
        String repoUrl = extractFirstRepoUrl(project);
        if (repoUrl == null) return ApiResponse.ok(Map.of("behind", 0));

        String repoName = GitRepoManager.extractRepoName(repoUrl);
        Path repoPath = gitRepoManager.getRepoPath(id, repoName);
        if (!repoPath.toFile().exists()) return ApiResponse.ok(Map.of("behind", 0));

        int behind = gitRepoManager.countCommitsBehind(repoPath);
        return ApiResponse.ok(Map.of("behind", behind));
    }

    private String extractFirstRepoUrl(CodeProject project) {
        if (project == null) return null;
        String urls = project.getRepoUrls();
        if (urls == null || urls.isBlank()) return null;
        return urls.split("\n")[0].trim();
    }
}
