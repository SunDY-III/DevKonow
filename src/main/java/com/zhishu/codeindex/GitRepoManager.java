package com.zhishu.codeindex;

import com.zhishu.common.GitException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

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

    /**
     * 克隆仓库到本地（先写临时目录，成功后 rename）。
     * 防止 clone 中途中断留下不完整目录。
     *
     * @param repoUrl   Git 仓库地址
     * @param localPath 最终存储路径
     * @return 克隆后的本地路径
     * @throws GitException 带错误码的异常
     */
    public Path clone(String repoUrl, Path localPath) throws GitException {
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

            // TODO: 私有仓库认证（用户标注）
            // if (credentials != null) {
            //     cloneCmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(user, pass));
            // }

            try (Git ignored = cloneCmd.call()) {
                log.info("Git clone 完成: {} → temp: {}", repoUrl, tempPath);
            }

            // 成功 → rename 临时目录为目标目录
            Files.move(tempPath, localPath, StandardCopyOption.ATOMIC_MOVE);
            log.info("Git clone rename 完成: {} → {}", tempPath, localPath);

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
     * 清理本地仓库。
     */
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
