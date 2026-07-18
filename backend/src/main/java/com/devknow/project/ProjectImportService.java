package com.devknow.project;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.devknow.codeindex.CodeIndexService;
import com.devknow.codeindex.GitRepoManager;
import com.devknow.common.GitException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一键导入编排服务。
 *
 * <p>Token 传递规则：Controller → importFromRepo() → handleFreshImport/handleReindex → GitRepoManager.clone()
 * token 仅存活在方法调用栈中，不存实例字段，避免泄露和线程安全问题。
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

    /** Redis 锁前缀：lock:reindex:{projectId} */
    private static final String LOCK_PREFIX = "lock:reindex:";

    // ======================== Redis 分布式锁 ========================

    /**
     * 尝试获取项目重建锁（SET NX EX 30）。
     * 同一时间只允许一个线程重建同一项目，多实例间也互斥。
     *
     * @param projectId 项目 ID
     * @return 锁标识（解锁时需要），获取失败返回 null
     */
    public String tryReindexLock(Long projectId) {
        if (projectId == null) return null;
        String lockKey = LOCK_PREFIX + projectId;
        String lockValue = UUID.randomUUID().toString();  // 唯一标识，解锁时校验
        Boolean locked = redis.opsForValue().setIfAbsent(lockKey, lockValue, Duration.ofSeconds(30));
        if (Boolean.TRUE.equals(locked)) {
            log.debug("获锁: projectId={}", projectId);
            return lockValue;
        }
        log.debug("锁已被占用: projectId={}", projectId);
        return null;
    }

    /**
     * 释放项目重建锁（Lua 脚本安全释放，只删除属于自己的锁）。
     */
    public void releaseReindexLock(Long projectId, String lockValue) {
        if (projectId == null || lockValue == null) return;
        String lockKey = LOCK_PREFIX + projectId;
        // Lua 脚本：比较 value 一致才删除，防止误删其他线程的锁
        String lua = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
        redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(lua, Long.class),
                List.of(lockKey), lockValue);
        log.debug("释放锁: projectId={}", projectId);
    }

    // ======================== 导入入口 ========================

    @Async
    public void importFromRepo(String repoUrl, boolean forceReindex, String credential,
                                boolean isSshKey, SseEmitter emitter) {
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));

        String repoName = GitRepoManager.extractRepoName(repoUrl);

        try {
            if (!importingUrls.add(repoUrl)) {
                sendError(emitter, closed, "DUPLICATE",
                        "此仓库正在导入中，请勿重复操作: " + repoName);
                return;
            }

            CodeProject existingProject = findExistingProject(repoUrl);

            if (existingProject != null && !forceReindex) {
                sendError(emitter, closed, "DUPLICATE",
                        "仓库「" + repoName + "」已导入。如需刷新代码索引请调用重新索引接口");
                return;
            }

            if (existingProject != null && forceReindex) {
                handleReindex(existingProject, repoName, repoUrl, credential, isSshKey, emitter, closed);
            } else {
                handleFreshImport(repoName, repoUrl, credential, isSshKey, emitter, closed);
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

    // ======================== 模式 A：首次导入 ========================

    private void handleFreshImport(String repoName, String repoUrl, String credential,
                                     boolean isSshKey, SseEmitter emitter, AtomicBoolean closed) {
        sendProgress(emitter, closed, "cloning", "正在克隆仓库...", 10);
        Path localPath = gitRepoManager.getRepoPath(0L, repoName);
        gitRepoManager.clone(repoUrl, localPath, credential, isSshKey);
        sendProgress(emitter, closed, "cloned", "仓库克隆完成", 25);

        sendProgress(emitter, closed, "scanning", "正在扫描项目结构...", 30);
        ProjectStructure structure = structureScanner.scan(localPath);
        sendProgress(emitter, closed, "scanned", String.format("扫描完成，发现 %d 个文件，主语言: %s",
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
        sendProgress(emitter, closed, "created", String.format("项目「%s」创建成功", projectName), 55);

        sendProgress(emitter, closed, "indexing", "正在索引代码...", 60);
        redis.opsForValue().set("index:status:" + projectId, "INDEXING");
        int methodCount = codeIndexService.indexProject(projectId, repoName, localPath, emitter, closed);
        project.setTotalMethods(methodCount);
        projectRepository.save(project);
        redis.delete("index:status:" + projectId);
        sendProgress(emitter, closed, "indexed", String.format("代码索引完成，共 %d 个方法", methodCount), 90);

        sendProgress(emitter, closed, "done", "导入完成！", 100);
        sendProjectEvent(emitter, closed, project);
    }

    // ======================== 模式 B：重新索引 ========================

    private void handleReindex(CodeProject project, String repoName, String repoUrl,
                                String credential, boolean isSshKey,
                                SseEmitter emitter, AtomicBoolean closed) {
        Long projectId = project.getId();

        // === Redis 分布式锁：防止并发重建同一项目 ===
        String lockValue = tryReindexLock(projectId);
        if (lockValue == null) {
            sendError(emitter, closed, "LOCKED", "项目正在重建中，请稍后再试");
            return;
        }
        try {
            sendProgress(emitter, closed, "pulling", "正在拉取最新代码...", 10);
            Path localPath = gitRepoManager.getRepoPath(projectId != null ? projectId : 0L, repoName);

            if (!localPath.toFile().exists()) {
                gitRepoManager.clone(repoUrl, localPath, credential, isSshKey);
            } else {
                gitRepoManager.pull(localPath);
            }

            // === HEAD 无变化检查：跳过重建 ===
            String currentHead = gitRepoManager.getHeadCommitHash(localPath);
            String lastCommit = gitRepoManager.getLastIndexedCommit(projectId, redis);
            if (currentHead != null && currentHead.equals(lastCommit)) {
                sendProgress(emitter, closed, "skipped", "代码已是最新，无需重建", 100);
                sendProjectEvent(emitter, closed, project);
                return;
            }

            sendProgress(emitter, closed, "pulled", "代码已更新", 25);
            redis.opsForValue().set("index:status:" + projectId, "INDEXING");

            if (lastCommit == null) {
                sendProgress(emitter, closed, "indexing", "全量索引...", 30);
                int methodCount = codeIndexService.indexProject(projectId, repoName, localPath, emitter, closed);
                project.setTotalMethods(methodCount);
            } else {
                sendProgress(emitter, closed, "diffing", "检测变更...", 35);
                List<String> changedFiles = gitRepoManager.diffChangedFiles(localPath, lastCommit);

                // commit hash diff 失败（force push）→ 尝试时间戳降级
                if (changedFiles.isEmpty()) {
                    String ts = redis.opsForValue().get("index:timestamp:" + projectId);
                    if (ts != null) {
                        log.warn("commit diff 为空，尝试时间戳降级: projectId={}", projectId);
                        changedFiles = gitRepoManager.diffSinceTimestamp(localPath, Long.parseLong(ts));
                    }
                }

                sendProgress(emitter, closed, "diffed", String.format("检测到 %d 个变更", changedFiles.size()), 45);
                sendProgress(emitter, closed, "indexing", "波及重建...", 55);
                codeIndexService.indexIncremental(projectId, repoName, localPath, changedFiles);
            }

            projectRepository.save(project);
            redis.delete("index:status:" + projectId);
            sendProgress(emitter, closed, "done", "重新索引完成！", 100);
            sendProjectEvent(emitter, closed, project);

        } finally {
            releaseReindexLock(projectId, lockValue);
        }
    }

    // ======================== Webhook 推送 ========================

    /**
     * Webhook 推送触发：Git 平台收到 push 事件后调用。
     * <p>
     * 只做增量重建（pull + diff → 波及重建），不做全量。
     * 使用 Redis 分布式锁防止同一项目并发重建。
     *
     * @param repoUrl 仓库 URL（从 webhook payload 中提取）
     * @return 操作结果描述
     */
    public String handleWebhookPush(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) return "仓库地址为空";

        CodeProject project = findExistingProject(repoUrl);
        if (project == null) return "项目未导入，忽略";

        Long projectId = project.getId();
        String lockValue = tryReindexLock(projectId);
        if (lockValue == null) return "项目正在重建中，忽略本次推送";

        try {
            String repoName = GitRepoManager.extractRepoName(repoUrl);
            Path localPath = gitRepoManager.getRepoPath(projectId, repoName);

            if (!localPath.toFile().exists()) return "本地仓库不存在，请重新导入";

            gitRepoManager.pull(localPath);

            String currentHead = gitRepoManager.getHeadCommitHash(localPath);
            String lastCommit = gitRepoManager.getLastIndexedCommit(projectId, redis);
            if (currentHead != null && currentHead.equals(lastCommit)) {
                return "代码已是最新，无需重建";
            }

            redis.opsForValue().set("index:status:" + projectId, "INDEXING");

            if (lastCommit == null) {
                codeIndexService.indexProject(projectId, repoName, localPath, null, null);
            } else {
                List<String> changedFiles = gitRepoManager.diffChangedFiles(localPath, lastCommit);
                if (changedFiles.isEmpty()) {
                    String ts = redis.opsForValue().get("index:timestamp:" + projectId);
                    if (ts != null) {
                        changedFiles = gitRepoManager.diffSinceTimestamp(localPath, Long.parseLong(ts));
                    }
                }
                codeIndexService.indexIncremental(projectId, repoName, localPath, changedFiles);
            }

            projectRepository.save(project);
            redis.delete("index:status:" + projectId);

            log.info("Webhook 重建完成: projectId={}, repoUrl={}", projectId, repoUrl);
            return "ok";

        } catch (Exception e) {
            log.error("Webhook 重建失败: projectId={}", projectId, e);
            redis.delete("index:status:" + projectId);
            return "重建失败: " + e.getMessage();
        } finally {
            releaseReindexLock(projectId, lockValue);
        }
    }

    // ======================== 删除项目 ========================

    public void deleteProject(Long projectId) {
        scanAndDelete("vec:" + projectId + ":code:*");
        scanAndDelete("ripple:callers:" + projectId + ":*");
        redis.delete("index:status:" + projectId);
        CodeProject project = projectRepository.findById(projectId).orElse(null);
        if (project != null) { project.setStatus("ARCHIVED"); projectRepository.save(project); }
    }

    private void scanAndDelete(String pattern) {
        try (var cursor = redis.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(pattern).count(200).build())) {
            while (cursor.hasNext()) redis.delete(cursor.next());
        }
    }

    // ======================== 辅助方法 ========================

    private CodeProject findExistingProject(String repoUrl) {
        List<CodeProject> active = projectRepository.findByStatus("ACTIVE");
        for (CodeProject p : active) {
            if (p.getRepoUrls() != null && p.getRepoUrls().contains(repoUrl)) return p;
        }
        return null;
    }

    private String deriveProjectName(String repoName, ProjectStructure structure) {
        if (repoName == null || repoName.equals("unknown")) return structure.getMainLanguage() + " Project";
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

    private void sendProgress(SseEmitter emitter, AtomicBoolean closed, String stage, String message, int percent) {
        if (closed.get()) return;
        try { emitter.send(SseEmitter.event().name("progress").data(
                objectMapper.writeValueAsString(new ProgressEvent(stage, message, percent))));
        } catch (IOException e) { closed.set(true); }
    }

    private void sendError(SseEmitter emitter, AtomicBoolean closed, String errorCode, String message) {
        if (closed.get()) return;
        try { emitter.send(SseEmitter.event().name("error").data(
                objectMapper.writeValueAsString(new ErrorEvent(errorCode, message))));
        } catch (IOException e) { closed.set(true); }
    }

    private void sendProjectEvent(SseEmitter emitter, AtomicBoolean closed, CodeProject project) {
        if (closed.get()) return;
        try { emitter.send(SseEmitter.event().name("project").data(
                objectMapper.writeValueAsString(new ProjectEvent(project.getId(), project.getName()))));
        } catch (IOException e) { closed.set(true); }
    }

    private record ProgressEvent(String stage, String message, int percent) {}
    private record ErrorEvent(String errorCode, String message) {}
    private record ProjectEvent(Long id, String name) {}
}
