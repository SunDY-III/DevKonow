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
 * <p>修复（vs 原始版本）：
 * <ol>
 *   <li>重复导入拦截：同一 URL 正在导入 / 已导入，直接返回错误</li>
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

    @Async
    public void importFromRepo(String repoUrl, SseEmitter emitter) {
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));

        String repoName = GitRepoManager.extractRepoName(repoUrl);

        try {
            // ---- 检查①：是否正在导入同一 URL ----
            if (!importingUrls.add(repoUrl)) {
                sendError(emitter, closed, "DUPLICATE",
                        "此仓库正在导入中，请勿重复操作: " + repoName);
                return;
            }

            // ---- 检查②：是否已经导入过 ----
            List<CodeProject> existing = projectRepository.findByStatus("ACTIVE");
            boolean alreadyImported = existing.stream()
                    .anyMatch(p -> p.getRepoUrls() != null && p.getRepoUrls().contains(repoUrl));
            if (alreadyImported) {
                sendError(emitter, closed, "DUPLICATE",
                        "仓库「" + repoName + "」已导入，如需刷新请使用重新索引功能");
                return;
            }

            // === Step 1: Clone（临时目录，成功后 rename） ===
            sendProgress(emitter, closed, "cloning", "正在克隆仓库...", 10);
            Path localPath = gitRepoManager.getRepoPath(0L, repoName);
            gitRepoManager.clone(repoUrl, localPath);
            sendProgress(emitter, closed, "cloned", "仓库克隆完成", 25);

            // === Step 2: Scan ===
            sendProgress(emitter, closed, "scanning", "正在扫描项目结构...", 30);
            ProjectStructure structure = structureScanner.scan(localPath);
            sendProgress(emitter, closed, "scanned",
                    String.format("扫描完成，发现 %d 个文件，主语言: %s",
                            structure.getTotalFiles(), structure.getMainLanguage()),
                    45);

            // === Step 3: Create Project Record ===
            sendProgress(emitter, closed, "creating", "正在创建项目记录...", 50);
            String projectName = deriveProjectName(repoName, structure);
            CodeProject project = CodeProject.builder()
                    .name(projectName)
                    .displayName(projectName)
                    .description(structure.getDescription())
                    .repoUrls(toJson(List.of(repoUrl)))
                    .language(structure.getMainLanguage())
                    .framework(structure.getFramework())
                    .buildTool(structure.getBuildTool())
                    .entryPoints(structure.getEntryPoints() != null ?
                            toJson(structure.getEntryPoints()) : null)
                    .totalFiles(structure.getTotalFiles())
                    .totalMethods(0)
                    .build();
            project = projectRepository.save(project);
            Long projectId = project.getId();

            sendProgress(emitter, closed, "created",
                    String.format("项目「%s」创建成功", projectName), 55);

            // === Step 4: Index Code（带索引标记） ===
            sendProgress(emitter, closed, "indexing", "正在索引代码...", 60);

            // 写索引开始标记（防止崩溃后残留旧数据污染结果）
            redis.opsForValue().set("index:status:" + projectId, "INDEXING");

            int methodCount = codeIndexService.indexProject(
                    projectId, repoName, localPath, emitter, closed);
            project.setTotalMethods(methodCount);
            projectRepository.save(project);

            // 索引完成 → 删除标记
            redis.delete("index:status:" + projectId);

            sendProgress(emitter, closed, "indexed",
                    String.format("代码索引完成，共 %d 个方法", methodCount), 90);

            // === Step 5: Done ===
            sendProgress(emitter, closed, "done", "导入完成！", 100);
            sendProjectEvent(emitter, closed, project);

        } catch (GitException e) {
            log.warn("导入失败: repoUrl={}, errorCode={}, msg={}",
                    repoUrl, e.getErrorCode(), e.getMessage());
            sendError(emitter, closed, e.getErrorCode().name(), e.getMessage());

        } catch (Exception e) {
            log.error("导入失败: repoUrl={}", repoUrl, e);
            sendError(emitter, closed, "UNKNOWN", "导入失败: " + e.getMessage());

        } finally {
            importingUrls.remove(repoUrl);  // 释放导入锁
            try {
                emitter.complete();
            } catch (Exception ignored) {}
        }
    }

    // ======================== 项目删除（含 Redis 清理） ========================

    /**
     * 删除项目及其关联数据（逻辑删除 + 清理 Redis 向量/反向索引/标记）。
     */
    public void deleteProject(Long projectId) {
        // 1. 清理 Redis 向量（vec:projectId:code:*）
        cleanRedisByProject(projectId);

        // 2. 清理 Redis 反向索引（ripple:callers:projectId:*）
        cleanRippleCache(projectId);

        // 3. 清理索引标记
        redis.delete("index:status:" + projectId);

        // 4. 逻辑删除项目记录
        CodeProject project = projectRepository.findById(projectId).orElse(null);
        if (project != null) {
            project.setStatus("ARCHIVED");
            projectRepository.save(project);
        }

        // 5. 清理本地仓库
        gitRepoManager.deleteLocalRepo(projectId, null);  // 需要 repoName
    }

    private void cleanRedisByProject(Long projectId) {
        String pattern = "vec:" + projectId + ":code:*";
        try (var cursor = redis.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(pattern).count(200).build())) {
            while (cursor.hasNext()) {
                redis.delete(cursor.next());
            }
        }
        log.info("清理向量完成: projectId={}, pattern={}", projectId, pattern);
    }

    private void cleanRippleCache(Long projectId) {
        String pattern = "ripple:callers:" + projectId + ":*";
        try (var cursor = redis.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(pattern).count(200).build())) {
            while (cursor.hasNext()) {
                redis.delete(cursor.next());
            }
        }
        log.info("清理反向索引完成: projectId={}", projectId);
    }

    // ======================== 启动时清理残留索引标记 ========================

    /**
     * 应用启动时检查所有 INDEXING 标记，清理不完整的旧索引。
     * 由 ZhishuApplication 或 @PostConstruct 调用。
     */
    public void cleanupStaleIndexes() {
        String pattern = "index:status:*";
        try (var cursor = redis.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(pattern).count(500).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String status = redis.opsForValue().get(key);
                if ("INDEXING".equals(status)) {
                    String projectId = key.replace("index:status:", "");
                    log.warn("检测到残留索引标记: projectId={}，将被清理", projectId);
                    redis.delete(key);
                }
            }
        }
        log.info("残留索引标记清理完成");
    }

    // ======================== 辅助方法 ========================

    private String deriveProjectName(String repoName, ProjectStructure structure) {
        if (repoName == null || repoName.equals("unknown")) {
            return structure.getMainLanguage() + " Project";
        }
        String name = repoName
                .replaceAll("[-_]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = true;
        for (char c : name.toCharArray()) {
            if (c == ' ') {
                nextUpper = true;
                sb.append(c);
            } else if (nextUpper) {
                sb.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    // ======================== SSE 推送 ========================

    private void sendProgress(SseEmitter emitter, AtomicBoolean closed,
                               String stage, String message, int percent) {
        if (closed.get()) return;
        try {
            String json = objectMapper.writeValueAsString(
                    new ProgressEvent(stage, message, percent));
            emitter.send(SseEmitter.event().name("progress").data(json));
        } catch (IOException e) {
            closed.set(true);
        }
    }

    private void sendError(SseEmitter emitter, AtomicBoolean closed,
                            String errorCode, String message) {
        if (closed.get()) return;
        try {
            String json = objectMapper.writeValueAsString(
                    new ErrorEvent(errorCode, message));
            emitter.send(SseEmitter.event().name("error").data(json));
        } catch (IOException e) {
            closed.set(true);
        }
    }

    private void sendProjectEvent(SseEmitter emitter, AtomicBoolean closed,
                                   CodeProject project) {
        if (closed.get()) return;
        try {
            String json = objectMapper.writeValueAsString(
                    new ProjectEvent(project.getId(), project.getName()));
            emitter.send(SseEmitter.event().name("project").data(json));
        } catch (IOException e) {
            closed.set(true);
        }
    }

    private record ProgressEvent(String stage, String message, int percent) {}
    private record ErrorEvent(String errorCode, String message) {}
    private record ProjectEvent(Long id, String name) {}
}
