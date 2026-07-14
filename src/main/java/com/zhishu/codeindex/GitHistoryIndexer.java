package com.zhishu.codeindex;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Git 历史索引器。
 *
 * <p>遍历 Git 仓库的 commit log，将每次提交提取为可检索的知识块。
 * 识别 fix/bug/hotfix 等关键词 → 标记为故障记录。
 *
 * TODO: Phase 2.2 实现（已创建占位，后续接入）
 */
@Slf4j
@Component
public class GitHistoryIndexer {

    public void indexCommits(Long projectId, String repoName, java.nio.file.Path repoPath) {
        log.info("GitHistoryIndexer: projectId={}, repoName={} (TODO: 待实现)", projectId, repoName);
        // Phase 2.2 实现: JGit 遍历 commit log
        // 1. Git.log().call() → 遍历所有 commit
        // 2. 提取 commit hash / author / message / date
        // 3. 生成 diff 摘要
        // 4. 按 commit 为粒度存入 Redis（vec:{projectId}:git:{commitId}）
        // 5. 匹配 fix/bug/hotfix → 标记为 incident
    }
}
