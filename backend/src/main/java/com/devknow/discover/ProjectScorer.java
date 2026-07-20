package com.devknow.discover;

import com.devknow.discover.GitHubSearchService.GitHubRepo;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 项目学习适龄评分器。
 *
 * <p>从多个维度评估 GitHub 项目是否适合作为学习素材：
 * <ul>
 *   <li>社区活跃度（stars / forks / 最近更新）</li>
 *   <li>文档完善度（README 长度、描述质量）</li>
 *   <li>技术匹配度（是否匹配用户想学的技术栈）</li>
 *   <li>难度适中性（项目规模适中，不过大也不过小）</li>
 * </ul>
 *
 * 参考 Reposcout 的评分模型：popularity + maintenance + completeness。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectScorer {

    private final ChatLanguageModel chatModel;

    /**
     * 对搜索结果打分并排序。
     *
     * @param repos      搜索结果
     * @param intent     用户学习意图
     * @return 按分数降序排序的列表
     */
    public List<GitHubRepo> scoreAndRank(List<GitHubRepo> repos,
                                          LearnIntentParser.LearnIntent intent) {
        if (repos == null || repos.isEmpty()) return repos;

        for (GitHubRepo repo : repos) {
            repo.setScore(calculateScore(repo, intent));
        }

        repos.sort(Comparator.comparingInt(GitHubRepo::getScore).reversed());
        return repos;
    }

    /**
     * 计算单个项目的学习适龄分数（0-100）。
     *
     * <p>评分维度及权重：
     * <ul>
     *   <li>社区活跃度 (40%)：stars + forks + 近期更新</li>
     *   <li>文档质量 (20%)：有描述、非空仓库</li>
     *   <li>技术匹配度 (20%)：语言/话题匹配用户意图</li>
     *   <li>规模适中性 (20%)：不太大也不太小</li>
     * </ul>
     */
    private int calculateScore(GitHubRepo repo, LearnIntentParser.LearnIntent intent) {
        int score = 0;

        // 1. 社区活跃度 (40分)
        int starsScore = Math.min(25, (int) (Math.log10(repo.getStars() + 1) * 6));
        int forksScore = Math.min(10, (int) (Math.log10(repo.getForks() + 1) * 5));
        int recencyScore = 5; // 默认5分，可以更精确地基于 updatedAt 计算
        score += starsScore + forksScore + recencyScore;

        // 2. 文档质量 (20分)
        if (repo.getDescription() != null && repo.getDescription().length() > 20) {
            score += 10;
        } else if (repo.getDescription() != null) {
            score += 5;
        }
        // 有 License 加分
        if (repo.getLicense() != null && !repo.getLicense().isBlank()
                && !"NOASSERTION".equals(repo.getLicense())) {
            score += 10;
        }

        // 3. 技术匹配度 (20分)
        if (intent.getTechnologies() != null) {
            String repoText = (repo.getTopics() + " " + repo.getDescription() + " " + repo.getLanguage()).toLowerCase();
            for (String tech : intent.getTechnologies()) {
                if (repoText.contains(tech.toLowerCase())) {
                    score += 5;
                }
            }
        }
        // 语言匹配
        if (intent.getFrameworks() != null && !intent.getFrameworks().isEmpty()) {
            String lang = repo.getLanguage() != null ? repo.getLanguage().toLowerCase() : "";
            for (String fw : intent.getFrameworks()) {
                if (lang.contains(fw.toLowerCase())) {
                    score += 5;
                }
            }
        }

        // 4. 规模适中性 (20分)
        // stars 在 500-50000 之间比较适合学习（太大太复杂，太小质量难保证）
        if (repo.getStars() >= 500 && repo.getStars() <= 50000) {
            score += 15;
        } else if (repo.getStars() > 100) {
            score += 10;
        }
        // forks > 100 说明被广泛使用
        if (repo.getForks() > 100) {
            score += 5;
        }

        return Math.min(100, score);
    }
}
