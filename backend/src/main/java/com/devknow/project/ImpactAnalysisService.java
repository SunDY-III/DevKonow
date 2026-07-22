package com.devknow.project;

import com.devknow.codeindex.*;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImpactAnalysisService {

    private final GitCommitRepository gitCommitRepo;
    private final CodeUnitEntityRepository codeUnitRepo;
    private final ChatLanguageModel chatModel;
    private final CodeProjectRepository projectRepository;
    private final com.devknow.codeindex.GitRepoManager gitRepoManager;

    private static final int MAX_CHANGED_FILES = 30;
    private static final int MAX_CALLERS_PER_METHOD = 5;
    private static final int MAX_DIFF_LINES = 100;

    public ImpactReport analyzeCommit(Long projectId, String commitHash, Consumer<String> onProgress) {
        if (onProgress == null) onProgress = msg -> {};

        if (projectId == null || commitHash == null || commitHash.isBlank()) {
            onProgress.accept("参数无效");
            return ImpactReport.failed("参数无效");
        }

        CodeProject project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            onProgress.accept("项目不存在");
            return ImpactReport.failed("项目不存在");
        }

        String repoName = project.getName();
        if (repoName == null || repoName.isBlank()) {
            onProgress.accept("项目名称为空");
            return ImpactReport.failed("项目名称为空");
        }
        String repoPath = gitRepoManager.getRepoPath(projectId, repoName).toString();

        onProgress.accept("正在获取 commit 信息...");
        var commitOpt = gitCommitRepo.findByProjectIdAndCommitHash(projectId, commitHash);

        ImpactReport.ImpactReportBuilder builder = ImpactReport.builder()
                .projectId(projectId).commitHash(commitHash)
                .commitMessage(commitOpt.map(GitCommitEntity::getMessage).orElse("无"))
                .authorName(commitOpt.map(GitCommitEntity::getAuthorName).orElse("未知"));

        try {
            // 步骤 1: git diff 提取变更文件（含实际代码变更行）
            onProgress.accept("步骤 1/3: 分析文件变更...");
            List<String> changedFiles = getChangedFiles(repoPath, commitHash);
            if (changedFiles.isEmpty()) {
                onProgress.accept("未检测到文件变更，或 commit 不存在");
                return ImpactReport.failed("未检测到文件变更");
            }
            builder.changedFiles(changedFiles);
            String diffContent = getDiffContent(repoPath, commitHash);

            // 步骤 2: 单次遍历文件，同时收集变更方法和调用方（避免两次 DB 查询）
            onProgress.accept("步骤 2/3: 追踪变更方法的影响范围...");
            Set<String> changedMethods = new HashSet<>();
            Map<String, List<String>> callersByFile = new HashMap<>();
            collectChanges(projectId, changedFiles, changedMethods, callersByFile);
            builder.changedMethods(new ArrayList<>(changedMethods));
            builder.affectedCallers(callersByFile);

            // 步骤 3: LLM 合成报告（含实际 diff 内容）
            onProgress.accept("步骤 3/3: 生成影响分析报告...");
            String report = generateReport(repoName, commitHash,
                    commitOpt.map(GitCommitEntity::getMessage).orElse(""),
                    changedFiles, callersByFile, diffContent);
            builder.llmReport(report != null ? report : "报告生成失败");

            builder.success(true);
            onProgress.accept("影响分析完成");

        } catch (Exception e) {
            log.error("影响分析失败", e);
            builder.success(false);
            onProgress.accept("分析失败: " + e.getMessage());
        }

        return builder.build();
    }

    /** 单次遍历文件，同时收集变更方法和调用方 */
    private void collectChanges(Long projectId, List<String> changedFiles,
                                Set<String> changedMethods,
                                Map<String, List<String>> callersByFile) {
        int limit = Math.min(changedFiles.size(), MAX_CHANGED_FILES);
        for (int i = 0; i < limit; i++) {
            String filePath = changedFiles.get(i);
            int spaceIdx = filePath.indexOf(' ');
            String cleanPath = spaceIdx > 0 ? filePath.substring(spaceIdx + 1) : filePath;
            List<CodeUnitEntity> units = codeUnitRepo.findByProjectIdAndFilePath(projectId, cleanPath);
            for (CodeUnitEntity unit : units) {
                String methodName = unit.getMethodName();
                if (methodName == null || methodName.isBlank()) continue;

                changedMethods.add(methodName);

                List<String> callers = codeUnitRepo.findCallersByMethodName(projectId, methodName);
                if (!callers.isEmpty()) {
                    List<String> limited = callers.size() > MAX_CALLERS_PER_METHOD
                            ? callers.subList(0, MAX_CALLERS_PER_METHOD)
                            : callers;
                    callersByFile.put(cleanPath + "#" + methodName, limited);
                }
            }
        }
    }

    private List<String> getChangedFiles(String repoPath, String commitHash) throws Exception {
        File repoDir = new File(repoPath);
        if (!new File(repoDir, ".git").exists()) return List.of();

        try (Git git = Git.open(repoDir); RevWalk revWalk = new RevWalk(git.getRepository())) {
            org.eclipse.jgit.lib.AnyObjectId commitId = git.getRepository().resolve(commitHash);
            if (commitId == null) return List.of();

            RevCommit commit = revWalk.parseCommit(commitId);
            if (commit.getParentCount() == 0) return List.of("initial commit");

            RevCommit parent = commit.getParent(0);
            List<String> files = new ArrayList<>();
            try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                diffFormatter.setRepository(git.getRepository());
                diffFormatter.setDetectRenames(true);
                List<DiffEntry> diffs = diffFormatter.scan(
                        prepareTreeParser(git.getRepository(), parent),
                        prepareTreeParser(git.getRepository(), commit));
                for (DiffEntry diff : diffs) {
                    String path = diff.getNewPath();
                    if (!path.equals("/dev/null")) files.add(diff.getChangeType() + " " + path);
                }
            }
            return files;
        }
    }

    private AbstractTreeIterator prepareTreeParser(Repository repository, RevCommit commit) throws Exception {
        RevWalk walk = new RevWalk(repository);
        try {
            RevTree tree = walk.parseTree(commit.getTree().getId());
            CanonicalTreeParser parser = new CanonicalTreeParser();
            try (ObjectReader reader = repository.newObjectReader()) {
                parser.reset(reader, tree.getId());
            }
            return parser;
        } finally {
            walk.dispose();
        }
    }

    /**
     * 获取 commit 的变更内容（实际增/删的代码行）。
     * 用于 LLM 分析变更实质，而非只看文件路径。
     */
    private String getDiffContent(String repoPath, String commitHash) {
        File repoDir = new File(repoPath);
        if (!new File(repoDir, ".git").exists()) return "";

        try (Git git = Git.open(repoDir)) {
            org.eclipse.jgit.lib.AnyObjectId commitId = git.getRepository().resolve(commitHash);
            if (commitId == null) return "";

            RevCommit commit = new RevWalk(git.getRepository()).parseCommit(commitId);
            if (commit.getParentCount() == 0) return "（初始提交，无 diff）";

            StringBuilder sb = new StringBuilder();
            int totalLines = 0;

            try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                formatter.setRepository(git.getRepository());
                formatter.setDetectRenames(true);
                formatter.setDiffComparator(org.eclipse.jgit.diff.RawTextComparator.DEFAULT);
                formatter.setContext(3); // 3 行上下文

                List<DiffEntry> diffs = formatter.scan(
                        prepareTreeParser(git.getRepository(), commit.getParent(0)),
                        prepareTreeParser(git.getRepository(), commit));

                for (DiffEntry diff : diffs) {
                    if (totalLines >= MAX_DIFF_LINES) break;
                    String path = diff.getNewPath();
                    if (path.equals("/dev/null")) path = diff.getOldPath();
                    sb.append("--- ").append(path).append('\n');
                    totalLines++;
                }
                return sb.toString();
            }
        } catch (Exception e) {
            log.warn("获取 diff 内容失败: {}", e.getMessage());
            return "";
        }
    }

    private String generateReport(String projectName, String commitHash, String commitMsg,
                                   List<String> changedFiles,
                                   Map<String, List<String>> callersByFile,
                                   String diffContent) {
        if (changedFiles == null || changedFiles.isEmpty()) return "无变更文件，无法生成报告";

        try {
            int fileLimit = Math.min(changedFiles.size(), 20);
            String changedFilesStr = String.join("\n", changedFiles.subList(0, fileLimit));
            String callersStr = formatCallers(callersByFile);

            String prompt = String.format("""
                    你是一个 DevOps 变更影响分析专家。分析以下 Git commit 的影响范围。

                    项目：%s
                    Commit：%s
                    提交信息：%s

                    变更文件（%d 个）：
                    %s

                    调用链影响：
                    %s

                    实际变更代码（diff）：
                    %s

                    请基于实际变更代码分析影响范围，输出 JSON 格式：
                    {
                      "summary": "一句话总结变更内容（基于 diff 实际改动）",
                      "risk_level": "LOW/MEDIUM/HIGH/CRITICAL",
                      "risk_reason": "风险等级的理由（基于变更代码的判断）",
                      "affected_services": ["受影响的模块或服务"],
                      "affected_interfaces": ["受影响的接口或方法"],
                      "recommended_actions": ["建议 1：...", "建议 2：..."],
                      "rollback_command": "git revert <commitHash>"
                    }
                    """,
                    projectName != null ? projectName.replace("%", "%%") : "",
                    commitHash, commitMsg != null ? commitMsg.replace("%", "%%") : "",
                    changedFiles.size(), changedFilesStr.replace("%", "%%"),
                    callersStr.replace("%", "%%"),
                    diffContent != null ? diffContent.replace("%", "%%") : "（无 diff 内容）"
            );

            ChatRequest request = ChatRequest.builder()
                    .messages(UserMessage.from(prompt))
                    .build();
            return chatModel.chat(request).aiMessage().text();
        } catch (Exception e) {
            log.warn("影响报告生成失败: {}", e.getMessage());
            return "{\"summary\":\"报告生成失败\",\"risk_level\":\"UNKNOWN\",\"recommended_actions\":[\"请重试或查看日志\"]}";
        }
    }

    private String formatCallers(Map<String, List<String>> callersByFile) {
        if (callersByFile == null || callersByFile.isEmpty()) return "（无）";
        StringBuilder sb = new StringBuilder();
        for (var entry : callersByFile.entrySet()) {
            sb.append("  方法 ").append(entry.getKey()).append(" 的调用方：\n");
            List<String> callers = entry.getValue();
            int limit = Math.min(callers.size(), MAX_CALLERS_PER_METHOD);
            for (int i = 0; i < limit; i++) {
                sb.append("    - ").append(callers.get(i)).append("\n");
            }
        }
        return sb.toString();
    }
}
