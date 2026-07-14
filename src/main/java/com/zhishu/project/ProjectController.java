package com.zhishu.project;

import com.zhishu.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
     */
    @PostMapping("/import")
    public SseEmitter importProject(@RequestParam String repoUrl) {
        SseEmitter emitter = new SseEmitter(300_000L);  // 5 分钟超时
        projectImportService.importFromRepo(repoUrl, emitter);
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
        return importProject(repoUrls.get(0));
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
     * 归档项目。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteProject(@PathVariable Long id) {
        projectService.deleteProject(id);
        return ApiResponse.ok(null);
    }

    /**
     * 追加仓库到已有项目。
     */
    @PostMapping("/{id}/repo")
    public ApiResponse<CodeProject> addRepo(@PathVariable Long id, @RequestParam String repoUrl) {
        CodeProject project = projectService.getProject(id);
        // 简单实现：仅更新 repoUrls 字段，不重新索引
        // 完整实现在 Phase 2.3
        return ApiResponse.ok(project);
    }
}
