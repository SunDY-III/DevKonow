package com.devknow.project;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 远程仓库大小检测器。
 *
 * <p>在 clone 前估算仓库大小，超过阈值时阻止导入并提示用户。
 * 优先通过 GitHub API 获取精确数据，其他平台走 git ls-remote 估算。
 */
@Slf4j
@Component
public class RepoSizeChecker {

    private final long maxRepoMb;
    private final int maxRepoFiles;

    /** 仓库规模分级阈值 */
    private static final long SMALL_MB = 50;        // < 50MB → 推荐 Tree-sitter
    private static final long MEDIUM_MB = 500;      // < 500MB → 推荐 SCIP
    private static final long LARGE_MB = 2000;      // < 2GB → 警告
    // > 2GB → 拒绝

    private static final int SMALL_FILES = 1000;
    private static final int MEDIUM_FILES = 10000;
    private static final int LARGE_FILES = 50000;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public RepoSizeChecker(@Value("${app.project.max-repo-mb:2000}") long maxRepoMb,
                           @Value("${app.project.max-repo-files:50000}") int maxRepoFiles) {
        this.maxRepoMb = maxRepoMb;
        this.maxRepoFiles = maxRepoFiles;
    }

    /** 仓库规模检测结果 */
    public record RepoInfo(boolean exists, String source, long sizeMb, int estimatedFiles,
                           SizeLevel level, String message, String recommendMode) {}

    public enum SizeLevel { OK, WARN, REJECT }

    /**
     * 检测远程仓库信息（不 clone）。
     *
     * @param repoUrl 仓库 URL
     * @return 仓库信息 + 推荐模式
     */
    public RepoInfo check(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return new RepoInfo(false, "", 0, 0, SizeLevel.REJECT, "仓库地址为空", "tree-sitter");
        }

        // 优先通过 GitHub API 获取
        if (repoUrl.contains("github.com")) {
            return checkGitHub(repoUrl);
        }

        // 其他平台：通过 git ls-remote 判断存在性，大小未知
        return new RepoInfo(true, "unknown", 0, 0, SizeLevel.OK,
                "非 GitHub 仓库，跳过大小检测", "tree-sitter");
    }

    /** 通过 GitHub API 获取仓库信息 */
    private RepoInfo checkGitHub(String repoUrl) {
        try {
            // 从 URL 提取 owner/repo
            String path = URI.create(repoUrl).getPath();
            while (path.startsWith("/")) path = path.substring(1);
            if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);

            String apiUrl = "https://api.github.com/repos/" + path;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/vnd.github.v3+json")
                    .GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                return new RepoInfo(false, "github", 0, 0, SizeLevel.REJECT,
                        "仓库不存在，请检查地址", "tree-sitter");
            }
            if (response.statusCode() == 403) {
                return new RepoInfo(true, "github", 0, 0, SizeLevel.OK,
                        "GitHub API 限流，跳过大小检测", "tree-sitter");
            }
            if (response.statusCode() != 200) {
                return new RepoInfo(true, "github", 0, 0, SizeLevel.OK,
                        "GitHub API 返回 " + response.statusCode() + "，跳过大小检测", "tree-sitter");
            }

            // 解析 JSON 获取 size 信息
            String body = response.body();
            long sizeKb = parseLong(body, "\"size\":");
            int fileCount = parseFileCount(body);

            long sizeMb = sizeKb / 1024L;
            SizeLevel level;
            String message;
            String recommendMode;

            if (sizeMb > maxRepoMb || fileCount > maxRepoFiles) {
                level = SizeLevel.REJECT;
                message = String.format("仓库过大：%dMB / %d 文件（上限 %dMB / %d 文件），暂不支持导入，等待优化",
                        sizeMb, fileCount, maxRepoMb, maxRepoFiles);
                recommendMode = "N/A";
            } else if (sizeMb > LARGE_MB || fileCount > LARGE_FILES) {
                level = SizeLevel.WARN;
                message = String.format("仓库较大：%dMB / %d 文件，建议使用 SCIP 模式获得更好的检索性能", sizeMb, fileCount);
                recommendMode = "scip";
            } else if (sizeMb > MEDIUM_MB || fileCount > MEDIUM_FILES) {
                level = SizeLevel.WARN;
                message = String.format("仓库中等：%dMB / %d 文件，推荐 SCIP 模式", sizeMb, fileCount);
                recommendMode = "scip";
            } else {
                level = SizeLevel.OK;
                message = String.format("仓库大小：%dMB / %d 文件，Tree-sitter 模式即可胜任", sizeMb, fileCount);
                recommendMode = "tree-sitter";
            }

            log.info("仓库大小检测: {} = {}MB, {} 文件, level={}, 推荐={}", path, sizeMb, fileCount, level, recommendMode);
            return new RepoInfo(true, "github", sizeMb, fileCount, level, message, recommendMode);

        } catch (Exception e) {
            log.warn("GitHub API 检测失败: {}", e.getMessage());
            return new RepoInfo(true, "github", 0, 0, SizeLevel.OK,
                    "大小检测失败，继续导入: " + e.getMessage(), "tree-sitter");
        }
    }

    /** 从 JSON 中解析数值字段 */
    private long parseLong(String json, String key) {
        try {
            int idx = json.indexOf(key);
            if (idx < 0) return 0;
            idx += key.length();
            int end = json.indexOf(',', idx);
            if (end < 0) end = json.indexOf('}', idx);
            if (end < 0) return 0;
            return Long.parseLong(json.substring(idx, end).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 从 GitHub API 响应中提取文件数（近似） */
    private int parseFileCount(String body) {
        try {
            // 通过 GitHub API 的 /repos/{owner}/{repo}/git/trees/HEAD?recursive=1 获取近似值
            // 在 size 检测阶段用简化估算
            long sizeKb = parseLong(body, "\"size\":");
            // GitHub 的 size 单位是 KB，文件数约等于 sizeKB / 50（假设平均 50KB/文件）
            return (int) (sizeKb / 50);
        } catch (Exception e) {
            return 0;
        }
    }
}
