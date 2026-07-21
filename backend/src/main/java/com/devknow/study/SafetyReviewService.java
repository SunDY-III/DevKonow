package com.devknow.study;

import com.devknow.codeindex.GitRepoManager;
import com.devknow.common.UserContext;
import com.devknow.governance.TokenAuditService;
import com.devknow.project.CodeProject;
import com.devknow.project.CodeProjectRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 代码安全审查服务。
 *
 * <p>对选取的代码段进行 7 维度全量横切面审查：
 * 线程并发 / 数据库 / 安全 / 资源管理 / LLM 成本 / 边界处理 / 日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SafetyReviewService {

    private final ChatLanguageModel chatModel;
    private final GitRepoManager gitRepoManager;
    private final CodeProjectRepository projectRepository;
    private final TokenAuditService tokenAuditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 对指定文件中的代码段进行安全审查。
     *
     * @param projectId  项目 ID
     * @param filePath   文件路径（相对于项目根目录，或绝对路径）
     * @param startLine  起始行号（从 1 开始）
     * @param endLine    结束行号
     * @return 审查报告
     */
    public SafetyReport review(Long projectId, String filePath, int startLine, int endLine) {
        long userId = UserContext.require();
        String code = readCodeRange(projectId, filePath, startLine, endLine);

        if (code == null) {
            return failed("无法读取文件: " + filePath);
        }

        String prompt = """
                你是一名资深代码安全审查专家。请对以下代码段进行 7 维度安全审查。

                代码文件：%s
                行范围：%d - %d

                ```
                %s
                ```

                对每个维度，输出发现的问题。如果没有问题则该维度返回空数组。

                输出 JSON（不要 markdown 标记）：
                {
                  "summary": "审查总览（一句话概括代码质量）",
                  "issues": [
                    {
                      "dimension": "threading|database|security|resource|cost|boundary|logging",
                      "severity": "critical|major|minor",
                      "title": "问题标题（15 字以内）",
                      "description": "问题描述（50 字以内）",
                      "lineRef": "行号引用，如 L42-L45",
                      "fix": "具体修复建议（50 字以内）",
                      "codeSnippet": "问题代码片段（可选，20 字以内）"
                    }
                  ],
                  "score": 1-100 的综合评分,
                  "passed": true/false（critical 或 major 超过 3 项为 false）
                }

                维度说明：
                - threading：线程安全、锁、并发集合、ThreadLocal、线程池
                - database：SQL 注入、N+1、事务边界、索引
                - security：XSS、敏感信息泄露、权限校验
                - resource：连接泄漏、文件句柄、SSE 清理
                - cost：LLM 重复调用、不必要的计算
                - boundary：空指针、数组越界、数值溢出、参数校验
                - logging：敏感数据打印、日志级别不当
                """.formatted(filePath, startLine, endLine, code);

        String json = chatModel.generate(prompt);
        tokenAuditService.record(userId, "SAFETY_REVIEW",
                code.length() / 2, json.length() / 2);

        try {
            String clean = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            SafetyReport report = objectMapper.readValue(clean, SafetyReport.class);
            report.setReviewedFile(filePath);
            report.setStartLine(startLine);
            report.setEndLine(endLine);
            if (report.getIssues() == null) report.setIssues(new ArrayList<>());
            return report;
        } catch (JsonProcessingException e) {
            log.warn("安全审查 JSON 解析失败", e);
            return failed("审查结果解析失败，请重试");
        }
    }

    private String readCodeRange(Long projectId, String filePath, int startLine, int endLine) {
        try {
            Path path;
            Path projectRoot = null;

            if (projectId != null) {
                CodeProject project = projectRepository.findById(projectId).orElse(null);
                if (project != null && project.getRepoUrls() != null) {
                    String repoUrl = extractFirstRepoUrl(project);
                    if (repoUrl != null) {
                        String repoName = GitRepoManager.extractRepoName(repoUrl);
                        projectRoot = gitRepoManager.getRepoPath(projectId, repoName).normalize();
                    }
                }
            }

            if (projectRoot != null) {
                // 相对路径 → 从项目根解析，并验证不越界
                path = projectRoot.resolve(filePath).normalize();
                if (!path.startsWith(projectRoot)) {
                    log.warn("路径越界: filePath={}, resolved={}, root={}", filePath, path, projectRoot);
                    return null;
                }
            } else {
                // 绝对路径直接读取
                path = Paths.get(filePath).normalize();
            }

            if (!path.toFile().exists() || !path.toFile().isFile()) {
                log.warn("文件不存在: {}", path);
                return null;
            }

            // 跳过过大文件
            if (path.toFile().length() > 500_000) {
                log.warn("文件过大: {} ({} bytes)", path, path.toFile().length());
                return null;
            }

            List<String> lines = Files.readAllLines(path);
            if (startLine < 1) startLine = 1;
            if (endLine > lines.size()) endLine = lines.size();
            if (startLine > endLine) return null;

            return String.join("\n", lines.subList(startLine - 1, endLine));

        } catch (Exception e) {
            log.error("读取代码失败: filePath={}", filePath, e);
            return null;
        }
    }

    private String extractFirstRepoUrl(CodeProject project) {
        if (project.getRepoUrls() == null) return null;
        try {
            List<String> urls = objectMapper.readValue(project.getRepoUrls(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return urls.isEmpty() ? null : urls.get(0);
        } catch (Exception e) {
            String raw = project.getRepoUrls().replaceAll("[\\[\\]\"]", "");
            return raw.split(",")[0].trim();
        }
    }

    private SafetyReport failed(String message) {
        SafetyReport r = new SafetyReport();
        r.setPassed(false);
        r.setSummary(message);
        r.setScore(0);
        return r;
    }

    // ======================== 数据模型 ========================

    @Data
    public static class SafetyReport {
        private String summary;
        private List<SafetyIssue> issues = new ArrayList<>();
        private int score;
        private boolean passed;
        private String reviewedFile;
        private int startLine;
        private int endLine;
    }

    @Data
    public static class SafetyIssue {
        private String dimension;
        private String severity;
        private String title;
        private String description;
        private String lineRef;
        private String fix;
        private String codeSnippet;
    }
}
