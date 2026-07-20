package com.devknow.discover;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GitHub 搜索服务。
 *
 * <p>通过 GitHub REST API 搜索仓库，支持关键词搜索和 trending 项目获取。
 * 参考 Clew Quest 的"问答式发现"模式，本服务提供结构化搜索能力。
 */
@Slf4j
@Service
public class GitHubSearchService {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.discover.github-token:}")
    private String githubToken;

    /** GitHub API 基础地址 */
    private static final String GITHUB_API = "https://api.github.com";
    /** 搜索超时 */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    public GitHubSearchService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .executor(Executors.newFixedThreadPool(4))  // 连接池：最多 4 个并发请求
                .build();
    }

    @PreDestroy
    public void shutdown() {
        if (httpClient.executor().isPresent()) {
            ((ExecutorService) httpClient.executor().get()).shutdown();
            log.info("GitHubSearchService HTTP client executor shut down");
        }
    }

    /**
     * 搜索 GitHub 仓库。
     *
     * @param keywords  搜索关键词（如 "spring cloud gateway"）
     * @param language  过滤语言（可选）
     * @param sort      排序方式：stars / updated / forks
     * @param maxResults 最大返回数（不超过 50）
     * @return 仓库列表
     */
    public List<GitHubRepo> searchRepos(List<String> keywords, String language,
                                         String sort, int maxResults) {
        String query = String.join(" ", keywords);
        if (language != null && !language.isBlank()) {
            query += " language:" + language;
        }
        // 只搜索高质量项目
        query += " stars:>100";

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = GITHUB_API + "/search/repositories?q=" + encodedQuery
                    + "&sort=" + (sort != null ? sort : "stars")
                    + "&order=desc&per_page=" + Math.min(maxResults, 50);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/vnd.github.v3+json");

            if (githubToken != null && !githubToken.isBlank()) {
                builder.header("Authorization", "Bearer " + githubToken);
                log.debug("使用 GitHub Token 认证搜索");
            }

            HttpResponse<String> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseSearchResults(response.body());
            } else {
                log.warn("GitHub 搜索失败: status={}, body={}",
                        response.statusCode(), response.body());
                return List.of();
            }

        } catch (Exception e) {
            log.error("GitHub 搜索异常", e);
            return List.of();
        }
    }

    /**
     * 获取仓库详情（含 README）。
     *
     * @param owner 仓库所有者
     * @param repo  仓库名
     * @return 仓库详情，失败返回 null
     */
    public GitHubRepo getRepoDetail(String owner, String repo) {
        try {
            String url = GITHUB_API + "/repos/" + owner + "/" + repo;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseRepo(response.body());
            }
        } catch (Exception e) {
            log.error("获取仓库详情失败: {}/{}", owner, repo, e);
        }
        return null;
    }

    // ======================== 数据模型 ========================

    @Data
    public static class GitHubRepo {
        private long id;
        private String fullName;
        private String name;
        private String owner;
        private String description;
        private String htmlUrl;
        private String language;
        private int stars;
        private int forks;
        private int openIssues;
        private String license;
        private String topics;
        private String updatedAt;
        private String readme;      // README 摘要
        private int score;          // 学习适龄评分（由 ProjectScorer 计算）
    }

    // ======================== 内部方法 ========================

    private List<GitHubRepo> parseSearchResults(String json) {
        List<GitHubRepo> repos = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode items = root.get("items");
            if (items == null || !items.isArray()) return repos;

            for (JsonNode item : items) {
                repos.add(parseRepoNode(item));
            }
        } catch (Exception e) {
            log.error("解析搜索 JSON 失败", e);
        }
        return repos;
    }

    private GitHubRepo parseRepo(String json) {
        try {
            return parseRepoNode(objectMapper.readTree(json));
        } catch (Exception e) {
            log.error("解析仓库 JSON 失败", e);
            return null;
        }
    }

    private GitHubRepo parseRepoNode(JsonNode node) {
        GitHubRepo repo = new GitHubRepo();
        repo.setId(node.has("id") ? node.get("id").asLong() : 0);
        repo.setFullName(node.has("full_name") ? node.get("full_name").asText() : "");
        repo.setName(node.has("name") ? node.get("name").asText() : "");

        if (node.has("owner") && node.get("owner").has("login")) {
            repo.setOwner(node.get("owner").get("login").asText());
        }

        repo.setDescription(node.has("description") && !node.get("description").isNull()
                ? node.get("description").asText() : "");
        repo.setHtmlUrl(node.has("html_url") ? node.get("html_url").asText() : "");
        repo.setLanguage(node.has("language") && !node.get("language").isNull()
                ? node.get("language").asText() : "Unknown");
        repo.setStars(node.has("stargazers_count") ? node.get("stargazers_count").asInt(0) : 0);
        repo.setForks(node.has("forks_count") ? node.get("forks_count").asInt(0) : 0);
        repo.setOpenIssues(node.has("open_issues_count") ? node.get("open_issues_count").asInt(0) : 0);

        if (node.has("license") && !node.get("license").isNull()) {
            repo.setLicense(node.get("license").has("spdx_id")
                    ? node.get("license").get("spdx_id").asText() : "");
        }

        if (node.has("topics") && node.get("topics").isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode t : node.get("topics")) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(t.asText());
            }
            repo.setTopics(sb.toString());
        }

        repo.setUpdatedAt(node.has("updated_at") ? node.get("updated_at").asText() : "");
        return repo;
    }
}
