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
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Git 仓库管理器。
 *
 * <p>处理：
 * <ul>
 *   <li>{@link GitException.ErrorCode#NETWORK_ERROR} — 前端显示重试按钮</li>
 *   <li>{@link GitException.ErrorCode#REPO_NOT_FOUND} — 返回"仓库不存在"</li>
 *   <li>{@link GitException.ErrorCode#PERMISSION_DENIED} — 提示配置 SSH Key</li>
 *   <li>{@link GitException.ErrorCode#CLONE_FAILED} — 其他错误</li>
 * </ul>
 */
@Slf4j
@Component
public class GitRepoManager {

    @Value("${app.code-index.repo-dir:/data/repos}")
    private String repoBaseDir;

    /**
     * 克隆仓库到本地。
     *
     * @param repoUrl   Git 仓库地址
     * @param localPath 本地存储路径
     * @return 克隆后的本地路径
     * @throws GitException 带错误码的异常
     */
    public Path clone(String repoUrl, Path localPath) throws GitException {
        File targetDir = localPath.toFile();

        // 确保父目录存在
        targetDir.getParentFile().mkdirs();

        try {
            log.info("Git clone: {} → {}", repoUrl, localPath);

            CloneCommand cloneCmd = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(targetDir)
                    .setCloneSubmodules(false)
                    .setTimeout(30);  // 30 秒超时

            // TODO: 私有仓库认证（用户标注）
            // if (credentials != null) {
            //     cloneCmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(user, pass));
            // }

            try (Git ignored = cloneCmd.call()) {
                log.info("Git clone 完成: {} ({} files)", repoUrl,
                        targetDir.listFiles() != null ? targetDir.listFiles().length : 0);
                return localPath;
            }

        } catch (InvalidRemoteException e) {
            throw new GitException(GitException.ErrorCode.REPO_NOT_FOUND,
                    "仓库不存在，请检查仓库地址是否正确: " + repoUrl);

        } catch (TransportException e) {
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
            // 其他 TransportException → 也归为网络错误
            throw new GitException(GitException.ErrorCode.NETWORK_ERROR,
                    "仓库连接失败: " + e.getMessage());

        } catch (GitAPIException e) {
            throw new GitException(GitException.ErrorCode.CLONE_FAILED,
                    "克隆失败: " + e.getMessage());

        } catch (Exception e) {
            throw new GitException(GitException.ErrorCode.CLONE_FAILED,
                    "克隆失败: " + e.getMessage());
        }
    }

    /**
     * 获取仓库的本地存储路径。
     *
     * @param projectId 项目 ID
     * @param repoName  仓库名（从 URL 提取）
     */
    public Path getRepoPath(Long projectId, String repoName) {
        return Paths.get(repoBaseDir, String.valueOf(projectId), repoName);
    }

    /**
     * 从 Git URL 提取仓库名。
     * 如 "https://github.com/user/repo.git" → "repo"
     *     "git@github.com:user/repo.git" → "repo"
     */
    public static String extractRepoName(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) return "unknown";

        String url = repoUrl.trim();

        // 去掉末尾的 .git
        if (url.endsWith(".git")) {
            url = url.substring(0, url.length() - 4);
        }

        // 取最后一段（/ 或 : 之后）
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
        File dir = path.toFile();
        if (dir.exists()) {
            deleteDirectory(dir);
            log.info("本地仓库已清理: {}", path);
        }
    }

    private void deleteDirectory(File dir) {
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
