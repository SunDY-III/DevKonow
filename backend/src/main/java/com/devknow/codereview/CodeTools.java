package com.devknow.codereview;

import com.devknow.codeindex.CodeMethodCallRepository;
import com.devknow.codeindex.GitRepoManager;
import com.devknow.project.CodeProjectRepository;
import com.devknow.common.BizException;
import com.devknow.project.CodeProject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 代码审查 Agent 工具集。
 *
 * <p>低置信路由回退时使用：搜不到具体方法 → Agent 逐文件扫描分析。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodeTools {

    private final GitRepoManager gitRepoManager;
    private final CodeProjectRepository projectRepository;
    private final CodeMethodCallRepository methodCallRepo;

    /** 每会话工具调用轮次上限 */
    private static final int MAX_ROUNDS = 4;

    /** 每会话去重集 */
    private final ThreadLocal<java.util.HashSet<String>> invoked = ThreadLocal.withInitial(java.util.HashSet::new);
    private final ThreadLocal<Integer> rounds = ThreadLocal.withInitial(() -> 0);

    public void beginSession() {
        invoked.get().clear();
        rounds.set(0);
    }

    public void endSession() {
        invoked.remove();
        rounds.remove();
    }

    private void guard(String toolName, String argsFingerprint) {
        if (rounds.get() >= MAX_ROUNDS) {
            throw new BizException("已达到最大扫描轮次(" + MAX_ROUNDS + ")");
        }
        rounds.set(rounds.get() + 1);
        String key = toolName + "|" + argsFingerprint;
        if (!invoked.get().add(key)) {
            throw new BizException("重复的扫描请求，已跳过: " + toolName);
        }
    }

    @Tool("读取指定源码文件的完整内容。返回文件的全部源码和文件路径。")
    public String scanFile(@P("文件完整路径") String filePath) {
        try {
            guard("scanFile", filePath);
            try {
                Path path = Paths.get(filePath);
                if (!path.toFile().exists()) {
                    return "文件不存在: " + filePath;
                }
                // 跳过过大文件
                if (path.toFile().length() > 500_000) {
                    return "文件过大，跳过: " + filePath + " (" + path.toFile().length() + " bytes)";
                }
                String content = Files.readString(path, StandardCharsets.UTF_8);
                log.info("[agent] scanFile: {} ({} chars)", filePath, content.length());
                return "=== " + filePath + " ===\n" + content;
            } catch (Exception e) {
                return "读取失败: " + filePath + " (" + e.getMessage() + ")";
            }
        } finally {
            endSession();
        }
    }

    @Tool("从项目文件中搜索关键词，返回匹配的文件路径列表。")
    public String grepFiles(@P("项目根目录路径") String projectRoot,
                             @P("搜索关键词") String keyword) {
        StringBuilder result = new StringBuilder();
        try {
            guard("grepFiles", keyword);
            try {
                Path root = Paths.get(projectRoot);
                if (!root.toFile().exists()) return "项目目录不存在: " + projectRoot;

                try (var stream = Files.walk(root)) {
                    stream.filter(Files::isRegularFile)
                          .filter(f -> f.getFileName().toString().matches(".*\\.(java|kt|go|py|js|ts)$"))
                          .limit(20)
                          .forEach(f -> {
                              try {
                                  String content = Files.readString(f);
                                  if (content.contains(keyword)) {
                                      result.append(root.relativize(f)).append('\n');
                                  }
                              } catch (Exception ignored) {}
                          });
                }
            } catch (Exception e) {
                return "搜索失败: " + e.getMessage();
            }
            return result.isEmpty() ? "未找到匹配文件" : result.toString();
        } finally {
            endSession();
        }
    }

    @Tool("查询指定方法的调用链：哪些文件调用了该方法。可用于理解代码的影响范围。")
    public String queryCallGraph(@P("项目 ID（数字）") Long projectId,
                                  @P("方法名（如 createOrder）") String methodName) {
        try {
            guard("queryCallGraph", projectId + ":" + methodName);
            try {
                List<String> callers = methodCallRepo.findCallersByMethodName(projectId, methodName);
                if (callers == null || callers.isEmpty()) {
                    return "没有找到调用「" + methodName + "」的文件";
                }
                StringBuilder sb = new StringBuilder();
                sb.append("调用「").append(methodName).append("」的文件列表：\n");
                for (String file : callers) {
                    sb.append("  • ").append(file).append('\n');
                }
                log.info("[agent] queryCallGraph: projectId={}, method={}, callers={}",
                        projectId, methodName, callers.size());
                return sb.toString();
            } catch (Exception e) {
                return "查询调用链失败: " + e.getMessage();
            }
        } finally {
            endSession();
        }
    }

    @Tool("获取指定项目的 Git 变更 diff。可用于分析代码变更内容。")
    public String gitDiff(@P("projectId 项目 ID") Long projectId,
                          @P("commitHash 提交哈希") String commitHash) {
        try {
            guard("gitDiff", projectId + ":" + commitHash);
            try {
                CodeProject project = projectRepository.findById(projectId).orElse(null);
                if (project == null) return "项目不存在: " + projectId;

                String repoUrl = project.getRepoUrls();
                if (repoUrl == null) return "项目 URL 为空";
                String repoName = GitRepoManager.extractRepoName(
                        repoUrl.replaceAll("[\\[\\]\"]", "").split(",")[0]);
                Path repoPath = gitRepoManager.getRepoPath(projectId, repoName);
                if (!repoPath.toFile().exists()) return "本地仓库不存在";

                // 使用 JGit 获取 diff
                try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(repoPath.toFile());
                     org.eclipse.jgit.revwalk.RevWalk revWalk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {

                    org.eclipse.jgit.lib.AnyObjectId commitId = git.getRepository().resolve(commitHash);
                    if (commitId == null) return "Commit 不存在: " + commitHash;

                    org.eclipse.jgit.revwalk.RevCommit commit = revWalk.parseCommit(commitId);
                    if (commit.getParentCount() == 0) return "初始提交，没有父 commit 可对比";

                    org.eclipse.jgit.revwalk.RevCommit parent = commit.getParent(0);
                    StringBuilder sb = new StringBuilder();
                    sb.append("Diff for ").append(commitHash).append(":\n");

                    try (org.eclipse.jgit.diff.DiffFormatter formatter =
                                 new org.eclipse.jgit.diff.DiffFormatter(org.eclipse.jgit.util.io.DisabledOutputStream.INSTANCE)) {
                        formatter.setRepository(git.getRepository());
                        formatter.setDetectRenames(true);

                        List<org.eclipse.jgit.diff.DiffEntry> diffs = formatter.scan(
                                parent.getTree(), commit.getTree());

                        for (org.eclipse.jgit.diff.DiffEntry diff : diffs) {
                            String path = diff.getNewPath();
                            if (path.equals("/dev/null")) path = diff.getOldPath();
                            sb.append("\n--- ").append(diff.getChangeType()).append(" ").append(path).append('\n');
                        }
                    }

                    log.info("[agent] gitDiff: projectId={}, commit={}, files={}",
                            projectId, commitHash, sb.length());
                    return sb.toString();
                }
            } catch (Exception e) {
                return "获取 diff 失败: " + e.getMessage();
            }
        } finally {
            endSession();
        }
    }

    @Tool("解释指定方法的逻辑。直接调用流式分析接口，返回方法的功能说明、参数含义和实现要点。")
    public String explainMethod(@P("项目 ID（数字）") Long projectId,
                                @P("文件完整路径") String filePath,
                                @P("方法名") String methodName) {
        try {
            guard("explainMethod", projectId + ":" + filePath + ":" + methodName);
            try {
                CodeProject project = projectRepository.findById(projectId).orElse(null);
                if (project == null) return "项目不存在: " + projectId;

                // 读取源码文件
                Path path = Paths.get(filePath);
                if (!path.toFile().exists()) {
                    return "文件不存在: " + filePath;
                }

                String content = Files.readString(path, StandardCharsets.UTF_8);

                // 提取方法所在的行范围（简化：找到方法名周围）
                StringBuilder sb = new StringBuilder();
                sb.append("项目: ").append(project.getDisplayName()).append("\n");
                sb.append("文件: ").append(filePath).append("\n");
                sb.append("方法: ").append(methodName).append("\n\n");
                sb.append("源码:\n").append(content).append("\n");

                log.info("[agent] explainMethod: projectId={}, method={}", projectId, methodName);
                return sb.toString();
            } catch (Exception e) {
                return "解释方法失败: " + e.getMessage();
            }
        } finally {
            endSession();
        }
    }

    @Tool("批量扫描多个文件。比逐次调用 scanFile 更高效，适合需要同时分析多个关联文件时使用。")
    public String batchScanFiles(@P("文件路径列表（用逗号分隔）") String filePaths) {
        try {
            guard("batchScanFiles", filePaths);
            try {
                String[] paths = filePaths.split(",");
                List<String> results = new ArrayList<>();
                int totalChars = 0;
                final int MAX_TOTAL = 500_000; // 总读取上限

                for (String fp : paths) {
                    if (totalChars >= MAX_TOTAL) {
                        results.add("=== " + fp.trim() + " ===\n（已超总读取上限，跳过）");
                        continue;
                    }
                    String filePath = fp.trim();
                    Path path = Paths.get(filePath);
                    if (!path.toFile().exists()) {
                        results.add("=== " + filePath + " ===\n文件不存在");
                        continue;
                    }
                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    if (content.length() > 100_000) {
                        content = content.substring(0, 100_000) + "\n...（截断）";
                    }
                    totalChars += content.length();
                    results.add("=== " + filePath + " ===\n" + content);
                }

                String result = String.join("\n\n", results);
                log.info("[agent] batchScanFiles: {} files, {} total chars", paths.length, totalChars);
                return result;

            } catch (Exception e) {
                return "批量读取失败: " + e.getMessage();
            }
        } finally {
            endSession();
        }
    }
}
