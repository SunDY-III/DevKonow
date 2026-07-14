package com.zhishu.codeindex;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
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
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Git 历史索引器。
 *
 * <p>遍历 Git 仓库的 commit log，提取每次提交的结构化信息。
 * 识别 fix/bug/hotfix 等关键词标记为故障记录，供后续检索。
 *
 * <p>索引结果按 commit 为粒度存入 Redis（vec:{projectId}:git:{commitHash}）。
 */
@Slf4j
@Component
public class GitHistoryIndexer {

    /** 标记为"故障"的 commit 关键词 */
    private static final List<String> INCIDENT_KEYWORDS = List.of(
            "fix", "bug", "hotfix", "repair", "patch", "error",
            "故障", "修复", "bugfix", "defect", "issue"
    );

    /**
     * 索引项目的 Git 历史。
     *
     * @param projectId 项目 ID
     * @param repoName  仓库名
     * @param repoPath  仓库本地路径
     * @return 索引的 commit 数量
     */
    public int indexCommits(Long projectId, String repoName, Path repoPath) {
        File repoDir = repoPath.toFile();
        if (!repoDir.exists() || !new File(repoDir, ".git").exists()) {
            log.warn("Git 历史索引跳过（不是 Git 仓库）: {}", repoPath);
            return 0;
        }

        int count = 0;
        int incidentCount = 0;

        try (Git git = Git.open(repoDir)) {
            LogCommand logCmd = git.log();
            logCmd.setMaxCount(2000);  // 最多索引最近 2000 条 commit

            try (RevWalk revWalk = new RevWalk(git.getRepository())) {
                Iterable<RevCommit> commits = logCmd.call();

                for (RevCommit commit : commits) {
                    try {
                        String message = commit.getFullMessage();
                        String hash = commit.getName();
                        String author = commit.getAuthorIdent() != null
                                ? commit.getAuthorIdent().getName() : "unknown";
                        String email = commit.getAuthorIdent() != null
                                ? commit.getAuthorIdent().getEmailAddress() : "";
                        long commitTime = commit.getCommitTime() * 1000L;  // → milliseconds

                        // 生成 diff 摘要（只取文件列表，不取完整 diff）
                        String diffSummary = generateDiffSummary(git.getRepository(), commit);

                        // 检测是否为故障修复
                        boolean isIncident = isIncidentCommit(message);
                        if (isIncident) {
                            incidentCount++;
                            log.info("故障 commit 已标记: {} ({})", hash, message.lines().findFirst().orElse(""));
                        }

                        // TODO: 存入 MySQL git_commit 表 + Redis 向量
                        // mysql: INSERT INTO git_commit (project_id, commit_hash, author_name, ...)
                        // redis: vec:{projectId}:git:{hash} → vector record
                        // Phase 2.2 暂不实现持久化，仅输出日志
                        if (count < 5 || isIncident) {
                            log.debug("commit[{}]: {} | {} | incident={}",
                                    hash.substring(0, 8),
                                    message.lines().findFirst().orElse("").trim(),
                                    author,
                                    isIncident);
                        }

                        count++;

                    } catch (Exception e) {
                        log.debug("跳过 commit: {}", e.getMessage());
                    }
                }
            }

            log.info("Git 历史索引完成: projectId={}, {} commits, {} incidents",
                    projectId, count, incidentCount);

        } catch (Exception e) {
            log.warn("Git 历史索引失败: projectId={}, repoPath={}", projectId, repoPath, e);
        }

        return count;
    }

    /**
     * 生成 diff 摘要（变更文件列表）。
     */
    private String generateDiffSummary(Repository repository, RevCommit commit) throws Exception {
        List<String> changedFiles = new ArrayList<>();

        if (commit.getParentCount() == 0) {
            return "initial commit";
        }

        RevCommit parent = commit.getParent(0);
        try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            diffFormatter.setRepository(repository);
            diffFormatter.setDetectRenames(true);

            List<DiffEntry> diffs = diffFormatter.scan(
                    prepareTreeParser(repository, parent),
                    prepareTreeParser(repository, commit)
            );

            for (DiffEntry diff : diffs) {
                String path = diff.getNewPath();
                if (!path.equals("/dev/null")) {
                    changedFiles.add(diff.getChangeType().name() + " " + path);
                }
            }
        }

        return String.join(", ", changedFiles.subList(0, Math.min(changedFiles.size(), 20)));
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
     * 判断 commit 是否为故障修复。
     */
    private boolean isIncidentCommit(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return INCIDENT_KEYWORDS.stream().anyMatch(lower::contains);
    }
}
