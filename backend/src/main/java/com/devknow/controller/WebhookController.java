package com.devknow.controller;

import com.devknow.common.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Git 平台 Webhook 接收端点。
 *
 * <p>接收 GitHub/GitLab/Gitee 的 push 事件，自动触发项目增量重建。
 * 使用 {@link ProjectImportService#tryReindexLock} 做并发去重，
 * 同一时间同一项目只允许一个重建任务运行。
 *
 * <p>支持平台：
 * <ul>
 *   <li>GitHub — 识别 repository.clone_url / html_url</li>
 *   <li>GitLab — 识别 project.git_http_url / web_url</li>
 *   <li>Gitee — 识别 repository.clone_url</li>
 * </ul>
 *
 * <p>安全：通过 Token 认证（query param token=xxx 或 header X-Git-Token）。
 */
@Slf4j
@RestController
@RequestMapping("/api/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final ProjectImportService projectImportService;

    /**
     * 通用 Webhook 入口。
     * 支持 GitHub / GitLab / Gitee 的 push 事件 JSON。
     */
    @PostMapping("/git")
    public ResponseEntity<Map<String, Object>> handlePush(@RequestBody Map<String, Object> payload,
                                                          @RequestParam(required = false) String token) {
        // Token 认证（如果配置了 token）
        if (token == null || token.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "missing token"));
        }

        try {
            String repoUrl = extractRepoUrl(payload);
            if (repoUrl == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "无法从 payload 中提取仓库地址"));
            }

            String result = projectImportService.handleWebhookPush(repoUrl);
            log.info("Webhook push: repoUrl={}, result={}", repoUrl, result);

            if ("ok".equals(result)) {
                return ResponseEntity.ok(Map.of("status", "ok", "repoUrl", repoUrl));
            } else {
                return ResponseEntity.ok(Map.of("status", "skipped", "reason", result, "repoUrl", repoUrl));
            }

        } catch (Exception e) {
            log.error("Webhook 处理失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 从 webhook payload 中提取仓库地址。
     * 兼容 GitHub / GitLab / Gitee 三种格式。
     */
    @SuppressWarnings("unchecked")
    private String extractRepoUrl(Map<String, Object> payload) {
        if (payload == null) return null;

        // GitHub / Gitee: payload.repository.clone_url
        Map<String, Object> repo = (Map<String, Object>) payload.get("repository");
        if (repo != null) {
            String cloneUrl = (String) repo.get("clone_url");
            if (cloneUrl != null && !cloneUrl.isBlank()) return cloneUrl;
            String htmlUrl = (String) repo.get("html_url");
            if (htmlUrl != null && !htmlUrl.isBlank()) return htmlUrl + ".git";
        }

        // GitLab: payload.project.git_http_url
        Map<String, Object> project = (Map<String, Object>) payload.get("project");
        if (project != null) {
            String httpUrl = (String) project.get("git_http_url");
            if (httpUrl != null && !httpUrl.isBlank()) return httpUrl;
            String webUrl = (String) project.get("web_url");
            if (webUrl != null && !webUrl.isBlank()) return webUrl + ".git";
        }

        return null;
    }
}
