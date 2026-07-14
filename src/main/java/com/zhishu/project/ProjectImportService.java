package com.zhishu.project;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishu.codeindex.CodeIndexService;
import com.zhishu.codeindex.GitRepoManager;
import com.zhishu.common.GitException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一键导入编排服务。
 *
 * <p>异步执行：clone → scan → create → index → done
 * 每步通过 SSE 推送进度，异常时推送到对应的错误类型。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectImportService {

    private final GitRepoManager gitRepoManager;
    private final StructureScanner structureScanner;
    private final CodeProjectRepository projectRepository;
    private final CodeIndexService codeIndexService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Async
    public void importFromRepo(String repoUrl, SseEmitter emitter) {
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));

        try {
            // === Step 1: Clone ===
            sendProgress(emitter, closed, "cloning", "正在克隆仓库...", 10);
            String repoName = GitRepoManager.extractRepoName(repoUrl);
            // Phase 1: 项目 ID 暂时用 0
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

            // === Step 4: Index Code ===
            sendProgress(emitter, closed, "indexing", "正在索引代码...", 60);
            int methodCount = codeIndexService.indexProject(projectId, repoName, localPath, emitter, closed);
            project.setTotalMethods(methodCount);
            projectRepository.save(project);
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
            try {
                emitter.complete();
            } catch (Exception ignored) {}
        }
    }

    private String deriveProjectName(String repoName, ProjectStructure structure) {
        // 从 repo 名推导中文项目名
        // repo-name → Repo Name
        if (repoName == null || repoName.equals("unknown")) {
            return structure.getMainLanguage() + " Project";
        }
        // 简单处理：替换分隔符为首字母大写
        String name = repoName
                .replaceAll("[-_]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        // 首字母大写
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

    // ======================== SSE 事件模型 ========================

    private record ProgressEvent(String stage, String message, int percent) {}
    private record ErrorEvent(String errorCode, String message) {}
    private record ProjectEvent(Long id, String name) {}
}
