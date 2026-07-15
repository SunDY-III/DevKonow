package com.zhishu.project;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishu.codeindex.CodeIndexService;
import com.zhishu.codeindex.GitRepoManager;
import com.zhishu.common.GitException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一键导入编排服务。
 *
 * <p>两种导入模式：
 * <ol>
 *   <li><b>首次导入</b>：clone → scan → create → 全量索引</li>
 *   <li><b>重新索引（波及重建）</b>：pull → diff → 增量索引</li>
 * </ol>
 *
 * <p>边界处理：
 * <ol>
 *   <li>重复导入拦截：同一 URL 正在导入 / 已导入但不勾选强制 → 返回错误</li>
 *   <li>SSE 断连停止索引：检测到断连后设置中断标记</li>
 *   <li>索引标记+崩溃清理：索引开始/结束标记写入 Redis</li>
 *   <li>删除时清理 Redis：联动清理向量 + 反向索引</li>
 *   <li>临时目录 clone：见 GitRepoManager</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectImportService {

    private final GitRepoManager gitRepoManager;
    private final StructureScanner structureScanner;
    private final CodeProjectRepository projectRepository;
    private final CodeIndexService codeIndexService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 正在导入中的仓库 URL（防重复导入） */
    private final Set<String> importingUrls = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // ======================== 导入入口 ========================

    /**
     * 一键导入。自动判断是首次导入还是重新索引。
     *
     * @param repoUrl     Git 仓库地址
     * @param forceReindex 是否强制重新索引（即使已导入）
     * @param emitter     SSE 推送器
     */
    /** 当前导入的私有仓库 Token（由 Controller 设置） */
    private String currentToken = null;

    public void setToken(String token) {
        this.currentToken = token;
    }

    @Async
    public void importFromRepo(String repoUrl, boolean forceReindex, SseEmitter emitter) {
        importFromRepo(repoUrl, forceReindex, null, emitter);
    }

    @Async
    public void importFromRepo(String repoUrl, boolean forceReindex, String token, SseEmitter emitter) {
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));

        String repoName = GitRepoManager.extractRepoName(repoUrl);
        this.currentToken = token;

        try {
            // ---- 检查：是否正在导入同一 URL ----
            if (!importingUrls.add(repoUrl)) {
                sendError(emitter, closed, "DUPLICATE",
                        "此仓库正在导入中，请勿重复操作: " + repoName);
                return;
            }

            // ---- 检查：是否已经导入过 ----
            CodeProject existingProject = findExistingProject(repoUrl);

            if (existingProject != null && !forceReindex) {
                sendError(emitter, closed, "DUPLICATE",
                        "仓库「" + repoName + "」已导入。如需刷新代码索引请调用重新索引接口");
                return;
            }

            if (existingProject != null && forceReindex) {
                // ======== 模式 B：重新索引（波及重建） ========
                handleReindex(existingProject, repoName, repoUrl, emitter, closed);
            } else {
                // ======== 模式 A：首次导入（全量索引） ========
                handleFreshImport(repoName, repoUrl, emitter, closed);
            }

        } catch (GitException e) {
            log.warn("导入失败: repoUrl={}, errorCode={}, msg={}",
                    repoUrl, e.getErrorCode(), e.getMessage());
            sendError(emitter, closed, e.getErrorCode().name(), e.getMessage());

        } catch (Exception e) {
            log.error("导入失败: repoUrl={}", repoUrl, e);
            sendError(emitter, closed, "UNKNOWN", "导入失败: " + e.getMessage());

        } finally {
            importingUrls.remove(repoUrl);
            try { emitter.complete(); } catch (Exception ignored) {}
        }
    }

    /**
     * 兼容老版本：不强制重新索引。
     */
    @Async
    public void importFromRepo(String repoUrl, SseEmitter emitter) {
        importFromRepo(repoUrl, false, emitter);
    }

    // ======================== 模式 A：首次导入 ========================

    private void handleFreshImport(String repoName, String repoUrl,
                                    SseEmitter emitter, AtomicBoolean closed) {
        sendProgress(emitter, closed, "cloning", "正在克隆仓库...", 10);
        Path localPath = gitRepoManager.getRepoPath(0L, repoName);
        gitRepoManager.clone(repoUrl, localPath, currentToken);
        sendProgress(emitter, closed, "cloned", "仓库克隆完成", 25);

        sendProgress(emitter, closed, "scanning", "正在扫描项目结构...", 30);
        ProjectStructure structure = structureScanner.scan(localPath);
        sendProgress(emitter, closed, "scanned",
                String.format("扫描完成，发现 %d 个文件，主语言: %s",
                        structure.getTotalFiles(), structure.getMainLanguage()), 45);

        sendProgress(emitter, closed, "creating", "正在创建项目记录...", 50);
        String projectName = deriveProjectName(repoName, structure);
        CodeProject project = CodeProject.builder()
                .name(projectName).displayName(projectName)
                .repoUrls(toJson(List.of(repoUrl)))
                .language(structure.getMainLanguage())
                .framework(structure.getFramework())
                .buildTool(structure.getBuildTool())
                .totalFiles(structure.getTotalFiles()).totalMethods(0)
                .build();
        project = projectRepository.save(project);
        Long projectId = project.getId();
        sendProgress(emitter, closed, "created",
                String.format("项目「%s」创建成功", projectName), 55);

        // 全量索引
        sendProgress(emitter, closed, "indexing", "正在索引代码...", 60);
        redis.opsForValue().set("index:status:" + projectId, "INDEXING");
        int methodCount = codeIndexService.indexProject(projectId, repoName, localPath, emitter, closed);
        project.setTotalMethods(methodCount);
        projectRepository.save(project);
        redis.delete("index:status:" + projectId);
        sendProgress(emitter, closed, "indexed",
                String.format("代码索引完成，共 %d 个方法", methodCount), 90);

        sendProgress(emitter, closed, "done", "导入完成！", 100);
        sendProjectEvent(emitter, closed, project);
    }

    // ======================== 模式 B：重新索引（波及重建） ========================

    private void handleReindex(CodeProject project, String repoName, String repoUrl,
                                SseEmitter emitter, AtomicBoolean closed) {
        Long projectId = project.getId();
        sendProgress(emitter, closed, "pulling", "正在拉取最新代码...", 10);

        Path localPath = gitRepoManager.getRepoPath(projectId != null ? projectId : 0L, repoName);
        if (!localPath.toFile().exists()) {
            // 本地仓库不存在（被误删），重新 clone（带 Token）
            gitRepoManager.clone(repoUrl, localPath, currentToken);
        } else {
            gitRepoManager.pull(localPath);
        }
        sendProgress(emitter, closed, "pulled", "代码已更新", 25);

        // 获取上次索引的 commit hash
        String lastCommit = gitRepoManager.getLastIndexedCommit(projectId, redis);
        if (lastCommit == null) {
            sendProgress(emitter, closed, "indexing", "未检测到上次索引记录，执行全量索引...", 30);
            redis.opsForValue().set("index:status:" + projectId, "INDEXING");
            int methodCount = codeIndexService.indexProject(projectId, repoName, localPath, emitter, closed);
            project.setTotalMethods(methodCount);
            projectRepository.save(project);
            redis.delete("index:status:" + projectId);
            sendProgress(emitter, closed, "indexed", String.format("全量索引完成，共 %d 个方法", methodCount), 90);
        } else {
            // 波及重建
            sendProgress(emitter, closed, "diffing", "正在检测变更...", 35);
            List<String> changedFiles = gitRepoManager.diffChangedFiles(localPath, lastCommit);
            sendProgress(emitter, closed, "diffed",
                    String.format("检测到 %d 个文件变更", changedFiles.size()), 45);

            sendProgress(emitter, closed, "indexing", "正在波及重建...", 55);
            redis.opsForValue().set("index:status:" + projectId, "INDEXING");
            int methodCount = codeIndexService.indexIncremental(projectId, repoName, localPath, changedFiles);
            project.setTotalFiles(project.getTotalFiles());  // 文件总数不变
            project.setTotalMethods(methodCount);
            projectRepository.save(project);
            redis.delete("index:status:" + projectId);
            sendProgress(emitter, closed, "indexed",
                    String.format("波及重建完成，共索引 %d 个方法", methodCount), 90);
        }

        // 重新扫描结构（文件数可能变化）
        ProjectStructure structure = structureScanner.scan(localPath);
        project.setTotalFiles(structure.getTotalFiles());
        project.setFramework(structure.getFramework());
        projectRepository.save(project);

        sendProgress(emitter, closed, "done", "重新索引完成！", 100);
        sendProjectEvent(emitter, closed, project);
    }

    // ======================== 项目删除（含 Redis 清理） ========================

    public void deleteProject(Long projectId) {
        scanAndDelete("vec:" + projectId + ":code:*");
        scanAndDelete("ripple:callers:" + projectId + ":*");
        redis.delete("index:status:" + projectId);

        CodeProject project = projectRepository.findById(projectId).orElse(null);
        if (project != null) {
            project.setStatus("ARCHIVED");
            projectRepository.save(project);
        }
    }

    private void scanAndDelete(String pattern) {
        try (var cursor = redis.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(pattern).count(200).build())) {
            while (cursor.hasNext()) redis.delete(cursor.next());
        }
    }

    // ======================== 启动时清理残留标记 ========================

    public void cleanupStaleIndexes() {
        try (var cursor = redis.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match("index:status:*").count(500).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                if ("INDEXING".equals(redis.opsForValue().get(key))) {
                    log.warn("检测到残留索引标记: {}", key);
                    redis.delete(key);
                }
            }
        }
    }

    // ======================== 辅助方法 ========================

    private CodeProject findExistingProject(String repoUrl) {
        List<CodeProject> activeProjects = projectRepository.findByStatus("ACTIVE");
        for (CodeProject p : activeProjects) {
            if (p.getRepoUrls() != null && p.getRepoUrls().contains(repoUrl)) {
                return p;
            }
        }
        return null;
    }

    private String deriveProjectName(String repoName, ProjectStructure structure) {
        if (repoName == null || repoName.equals("unknown")) {
            return structure.getMainLanguage() + " Project";
        }
        String name = repoName.replaceAll("[-_]", " ").replaceAll("\\s+", " ").trim();
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = true;
        for (char c : name.toCharArray()) {
            if (c == ' ') { nextUpper = true; sb.append(c); }
            else if (nextUpper) { sb.append(Character.toUpperCase(c)); nextUpper = false; }
            else { sb.append(c); }
        }
        return sb.toString();
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (JsonProcessingException e) { return "[]"; }
    }

    // ======================== SSE 推送 ========================

    private void sendProgress(SseEmitter emitter, AtomicBoolean closed,
                               String stage, String message, int percent) {
        if (closed.get()) return;
        try {
            String json = objectMapper.writeValueAsString(new ProgressEvent(stage, message, percent));
            emitter.send(SseEmitter.event().name("progress").data(json));
        } catch (IOException e) { closed.set(true); }
    }

    private void sendError(SseEmitter emitter, AtomicBoolean closed,
                            String errorCode, String message) {
        if (closed.get()) return;
        try {
            String json = objectMapper.writeValueAsString(new ErrorEvent(errorCode, message));
            emitter.send(SseEmitter.event().name("error").data(json));
        } catch (IOException e) { closed.set(true); }
    }

    private void sendProjectEvent(SseEmitter emitter, AtomicBoolean closed, CodeProject project) {
        if (closed.get()) return;
        try {
            String json = objectMapper.writeValueAsString(new ProjectEvent(project.getId(), project.getName()));
            emitter.send(SseEmitter.event().name("project").data(json));
        } catch (IOException e) { closed.set(true); }
    }

    private record ProgressEvent(String stage, String message, int percent) {}
    private record ErrorEvent(String errorCode, String message) {}
    private record ProjectEvent(Long id, String name) {}
}
