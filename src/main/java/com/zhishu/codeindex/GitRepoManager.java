package com.zhishu.codeindex;

import com.zhishu.common.GitException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;

/**
 * Git 仓库管理器。
 *
 * <p>处理：
 * <ul>
 *   <li>{@link GitException.ErrorCode#NETWORK_ERROR} — 前端显示重试按钮</li>
 *   <li>{@link GitException.ErrorCode#REPO_NOT_FOUND} — 返回"仓库不存在"</li>
 *   <li>{@link GitException.ErrorCode#PERMISSION_DENIED} — 提示配置 SSH Key</li>
 *   <li>clone 中断 → 临时目录写入，成功后 rename，失败自动清理</li>
 * </ul>
 */
@Slf4j
@Component
public class GitRepoManager {

    @Value("${app.code-index.repo-dir:/data/repos}")
    private String repoBaseDir;

    @Value("${app.code-index.max-repo-size-mb:500}")
    private int maxRepoSizeMb;

    /**
     * 克隆仓库到本地（先写临时目录，成功后 rename）。
     * 防止 clone 中途中断留下不完整目录。
     *
     * @param repoUrl   Git 仓库地址
     * @param localPath 最终存储路径
     * @param token     私有仓库访问 Token（如 GitHub PAT），可为 null
     * @return 克隆后的本地路径
     * @throws GitException 带错误码的异常
     */
    public Path clone(String repoUrl, Path localPath, String token) throws GitException {
        File targetDir = localPath.toFile();

        // 如果目标已存在，先删除（重新导入场景）
        if (targetDir.exists()) {
            deleteDirectory(targetDir);
        }

        // 临时目录：同级目录下加 .tmp 后缀
        Path tempPath = localPath.resolveSibling(localPath.getFileName() + ".tmp");
        File tempDir = tempPath.toFile();
        if (tempDir.exists()) {
            deleteDirectory(tempDir);  // 清理上次残留的临时目录
        }
        tempDir.getParentFile().mkdirs();

        try {
            log.info("Git clone: {} → {} (temp: {})", repoUrl, localPath, tempPath);

            CloneCommand cloneCmd = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(tempDir)
                    .setCloneSubmodules(false)
                    .setTimeout(30);  // 30 秒超时

            // 私有仓库认证：GitHub/Gitee/GitLab Personal Access Token
            if (token != null && !token.isBlank()) {
                cloneCmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""));
                log.info("使用 Token 认证克隆私有仓库");
            }

            try (Git ignored = cloneCmd.call()) {
                log.info("Git clone 完成: {} → temp: {}", repoUrl, tempPath);
            }

            // 成功 → rename 临时目录为目标目录
            Files.move(tempPath, localPath, StandardCopyOption.ATOMIC_MOVE);
            log.info("Git clone rename 完成: {} → {}", tempPath, localPath);

            // 检查仓库大小（超过限制时清理并报错）
            long sizeMb = calculateSizeMb(localPath);
            if (sizeMb > maxRepoSizeMb) {
                deleteDirectory(localPath.toFile());
                throw new GitException(GitException.ErrorCode.REPO_TOO_LARGE,
                        String.format("仓库大小 %dMB 超过限制 %dMB，请选择更轻量的仓库或联系管理员调整限制",
                                sizeMb, maxRepoSizeMb));
            }
            log.info("仓库大小: {}MB (上限: {}MB)", sizeMb, maxRepoSizeMb);

            return localPath;

        } catch (InvalidRemoteException e) {
            deleteDirectory(tempDir);
            throw new GitException(GitException.ErrorCode.REPO_NOT_FOUND,
                    "仓库不存在，请检查仓库地址是否正确: " + repoUrl);

        } catch (TransportException e) {
            deleteDirectory(tempDir);
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

            if (msg.contains("not found") || msg.contains("404") || msg.contains("no such")) {
                throw new GitException(GitException.ErrorCode.REPO_NOT_FOUND,
                        "仓库不存在，请检查仓库地址是否正确: " + repoUrl);
            }
            if (msg.contains("auth") || msg.contains("permission") || msg.contains("denied")
                    || msg.contains("403") || msg.contains("401") || msg.contains("key")) {
                throw new GitException(GitException.ErrorCode.PERMISSION_DENIED,
                        "无权限访问仓库，请配置 SSH Key 后重试: " + repoUrl);
            }
            if (msg.contains("timeout") || msg.contains("connect") || msg.contains("refused")
                    || msg.contains("network") || msg.contains("resolve")) {
                throw new GitException(GitException.ErrorCode.NETWORK_ERROR,
                        "网络连接失败，请检查网络后重试: " + e.getMessage());
            }
            throw new GitException(GitException.ErrorCode.NETWORK_ERROR,
                    "仓库连接失败: " + e.getMessage());

        } catch (GitAPIException | IOException e) {
            deleteDirectory(tempDir);
            throw new GitException(GitException.ErrorCode.CLONE_FAILED,
                    "克隆失败: " + e.getMessage());

        } catch (Exception e) {
            deleteDirectory(tempDir);
            throw new GitException(GitException.ErrorCode.CLONE_FAILED,
                    "克隆失败: " + e.getMessage());
        }
    }

    /**
     * 获取仓库的本地存储路径。
     */
    public Path getRepoPath(Long projectId, String repoName) {
        return Paths.get(repoBaseDir, String.valueOf(projectId), repoName);
    }

    /**
     * 从 Git URL 提取仓库名。
     */
    public static String extractRepoName(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) return "unknown";
        String url = repoUrl.trim();
        if (url.endsWith(".git")) {
            url = url.substring(0, url.length() - 4);
        }
        int slash = url.lastIndexOf('/');
        int colon = url.lastIndexOf(':');
        int idx = Math.max(slash, colon);
        if (idx >= 0 && idx < url.length() - 1) {
            return url.substring(idx + 1);
        }
        return url;
    }

    /**
     * 计算仓库目录的总大小（MB）。
     * 遍历所有文件求和，跳过无法访问的文件。
     */
    public static long calculateSizeMb(Path repoPath) {
        if (repoPath == null || !repoPath.toFile().exists()) return 0;
        try {
            long totalBytes;
            try (var stream = java.nio.file.Files.walk(repoPath)) {
                totalBytes = stream.filter(java.nio.file.Files::isRegularFile)
                        .mapToLong(f -> {
                            try { return java.nio.file.Files.size(f); }
                            catch (java.io.IOException e) { return 0; }
                        })
                        .sum();
            }
            return totalBytes / (1024 * 1024);
        } catch (java.io.IOException e) {
            log.warn("计算仓库大小失败: {}", repoPath, e);
            return 0;
        }
    }

    // ======================== 重新索引 ========================

    /**
     * 拉取仓库最新变更（不会改变本地未提交的修改）。
     */
    public String pull(Path repoPath) {
        try (Git git = Git.open(repoPath.toFile())) {
            var result = git.pull().call();
            if (result.isSuccessful()) {
                log.info("Git pull 成功: {}", repoPath);
            } else {
                log.warn("Git pull 未成功: {}", result);
            }
            // 返回最新的 HEAD hash
            return getHeadCommitHash(repoPath);
        } catch (Exception e) {
            log.warn("Git pull 失败: {}", repoPath, e);
            return null;
        }
    }

    /**
     * 验证仓库地址和 Token 是否有效。
     * 通过 LSRemote 轻量级连接远程仓库，不下载任何数据。
     *
     * @param repoUrl Git 仓库地址
     * @param token   私有仓库 Token（可为 null）
     * @return 远程仓库信息（HEAD 指向的分支和最新提交）
     * @throws GitException REPO_NOT_FOUND / PERMISSION_DENIED / NETWORK_ERROR
     */
    public String verifyRepo(String repoUrl, String token) throws GitException {
        try {
            LsRemoteCommand cmd = Git.lsRemoteRepository()
                    .setRemote(repoUrl)
                    .setTimeout(15);

            if (token != null && !token.isBlank()) {
                cmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""));
            }

            var refs = cmd.call();
            String head = refs.stream()
                    .filter(r -> "HEAD".equals(r.getName()))
                    .findFirst()
                    .map(r -> r.getObjectId() != null ? r.getObjectId().getName() : "unknown")
                    .orElse("unknown");

            log.info("仓库验证通过: {} HEAD={}", sanitizeUrl(repoUrl), head.substring(0, 8));
            return "仓库可访问 | HEAD: " + head.substring(0, 8);

        } catch (InvalidRemoteException e) {
            throw new GitException(GitException.ErrorCode.REPO_NOT_FOUND,
                    "仓库不存在，请检查地址是否正确: " + repoUrl);
        } catch (TransportException e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("auth") || msg.contains("permission") || msg.contains("denied")
                    || msg.contains("403") || msg.contains("401")) {
                throw new GitException(GitException.ErrorCode.PERMISSION_DENIED,
                        "Token 无效或已过期，请重新生成后重试: " + repoUrl);
            }
            if (msg.contains("not found") || msg.contains("404")) {
                throw new GitException(GitException.ErrorCode.REPO_NOT_FOUND,
                        "仓库不存在，请检查地址是否正确: " + repoUrl);
            }
            throw new GitException(GitException.ErrorCode.NETWORK_ERROR,
                    "仓库连接失败: " + e.getMessage());
        } catch (Exception e) {
            throw new GitException(GitException.ErrorCode.CLONE_FAILED,
                    "仓库验证失败: " + e.getMessage());
        }
    }

    /** 脱敏日志中的 repoUrl（去掉 token 参数） */
    public static String sanitizeUrl(String url) {
        if (url == null) return null;
        return url.replaceAll("[?&]token=[^&]+", "?token=***");
    }

    /**
     * 获取上次索引的 commit hash（从 Redis）。
     */
    public String getLastIndexedCommit(Long projectId,
                                        org.springframework.data.redis.core.StringRedisTemplate redis) {
        return redis.opsForValue().get("index:commit:" + projectId);
    }

    // ======================== 新提交检测（Webhook / 定时轮询共用） ========================

    /**
     * 检查本地仓库落后远程多少次提交。
     * 用于两种场景：
     * <ul>
     *   <li>前端轮询：检测到有新提交时显示"刷新"按钮</li>
     *   <li>定时任务：behind > 0 时自动触发波及重建</li>
     * </ul>
     *
     * @return 落后次数，0 = 已是最新，-1 = 检测失败
     */
    public int countCommitsBehind(Path repoPath) {
        if (repoPath == null || !repoPath.toFile().exists()) return -1;

        try (Git git = Git.open(repoPath.toFile())) {
            // fetch 远程最新（不合并）
            git.fetch().call();

            ObjectId head = git.getRepository().resolve("HEAD");
            ObjectId remote = git.getRepository().resolve("origin/HEAD");

            if (head == null || remote == null) {
                // 尝试 origin/main 或 origin/master
                remote = git.getRepository().resolve("origin/main");
                if (remote == null) remote = git.getRepository().resolve("origin/master");
            }
            if (head == null || remote == null) return -1;

            try (RevWalk walk = new RevWalk(git.getRepository())) {
                walk.markStart(walk.parseCommit(remote));
                walk.markUninteresting(walk.parseCommit(head));
                int count = 0;
                for (RevCommit ignored : walk) count++;
                return count;
            }

        } catch (Exception e) {
            log.warn("检查新提交失败: {}", repoPath, e);
            return -1;
        }
    }

    // ======================== 波及重建：Git diff ========================

    /**
     * 获取当前 HEAD 的 commit hash。
     */
    public String getHeadCommitHash(Path repoPath) {
        try (Git git = Git.open(repoPath.toFile())) {
            var commits = git.log().setMaxCount(1).call();
            if (commits.iterator().hasNext()) {
                return commits.iterator().next().getName();
            }
        } catch (Exception e) {
            log.warn("获取 HEAD commit hash 失败: {}", repoPath, e);
        }
        return null;
    }

    /**
     * 对比两个 commit 之间的变更文件列表。
     *
     * @param repoPath     仓库本地路径
     * @param sinceCommit  起始 commit hash（不含此 commit 本身）
     * @return 变更文件列表（相对于仓库根目录的路径）
     */
    public List<String> diffChangedFiles(Path repoPath, String sinceCommit) {
        List<String> changedFiles = new ArrayList<>();
        if (sinceCommit == null) return changedFiles;

        try (Git git = Git.open(repoPath.toFile())) {
            ObjectId sinceId = git.getRepository().resolve(sinceCommit);
            ObjectId headId = git.getRepository().resolve("HEAD");

            if (sinceId == null || headId == null) {
                log.warn("diff 失败: 无法解析 commit hash, since={}", sinceCommit);
                return changedFiles;
            }

            try (ObjectReader reader = git.getRepository().newObjectReader();
                 RevWalk walk = new RevWalk(git.getRepository())) {

                RevCommit sinceCommitObj = walk.parseCommit(sinceId);
                RevCommit headCommitObj = walk.parseCommit(headId);

                try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                    diffFormatter.setRepository(git.getRepository());
                    diffFormatter.setDetectRenames(true);

                    List<DiffEntry> diffs = diffFormatter.scan(
                            prepareTreeParser(git.getRepository(), sinceCommitObj),
                            prepareTreeParser(git.getRepository(), headCommitObj)
                    );

                    for (DiffEntry diff : diffs) {
                        String path = diff.getNewPath();
                        if (!path.equals("/dev/null")) {
                            changedFiles.add(path);
                        }
                    }
                }
            }

            log.info("Git diff: since={}, changedFiles={}", sinceCommit.substring(0, 8), changedFiles.size());

        } catch (Exception e) {
            log.warn("Git diff 失败: {}", repoPath, e);
        }

        return changedFiles;
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

    // ======================== 清理 ========================
    public void deleteLocalRepo(Long projectId, String repoName) {
        Path path = getRepoPath(projectId, repoName);
        deleteDirectory(path.toFile());
        // 也清理可能的残留临时目录
        Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
        deleteDirectory(tempPath.toFile());
        log.info("本地仓库已清理: {} (及其 tmp)", path);
    }

    private void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }
}
