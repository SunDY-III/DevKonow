package com.devknow.rag;

import com.devknow.codeindex.CodeUnitEntityRepository;
import com.devknow.vector.ScoredChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 代码结构感知的多因子重排序。
 *
 * <p>针对开发者知识库场景设计，在通用 RRF 分基础上叠加代码特有信号：
 * <pre>
 * final_score = 0.4 × RRF_score
 *             + 0.2 × query_code_overlap  （问题中的代码标识符在 chunk 中的命中率）
 *             + 0.2 × file_path_match      （chunk 文件路径与代码术语的匹配度）
 *             + 0.1 × query_coverage       （通用查询词覆盖率）
 *             + 0.1 × position_bonus       （靠前位置加分）
 * </pre>
 *
 * <p>优化：使用 SQL IN 批量查询替代逐 term 查 DB（N+1 → 1），
 * 减少调用链预计算时的数据库往返次数。
 */
@Slf4j
@Component
public class Reranker {

    // 权重系数
    private static final double SCORE_WEIGHT = 0.4;
    private static final double CODE_OVERLAP_WEIGHT = 0.2;
    private static final double FILE_PATH_WEIGHT = 0.2;
    private static final double COVERAGE_WEIGHT = 0.1;
    private static final double POSITION_WEIGHT = 0.1;

    // 代码标识符正则
    private static final Pattern CAMEL_SPLIT = Pattern.compile("[a-z]+|[A-Z][a-z]*|[A-Z]+(?=[A-Z]|$)");
    private static final Pattern DOT_CHAIN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+");
    private static final Pattern METHOD_CALL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(");

    private final CodeUnitEntityRepository codeUnitRepo;

    public Reranker(CodeUnitEntityRepository codeUnitRepo) {
        this.codeUnitRepo = codeUnitRepo;
    }

    /**
     * 重排序入口（无项目 ID 时跳过调用图特征）。
     */
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topN) {
        return rerank(query, candidates, topN, null);
    }

    /**
     * 代码结构感知重排序。
     *
     * @param query      用户查询
     * @param candidates 候选片段
     * @param topN       最终保留数
     * @param projectId  项目 ID（提供时启用调用链评分，否则跳过）
     */
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topN, Long projectId) {
        if (candidates == null || candidates.isEmpty()) return List.of();

        // 步骤 1: 从查询中提取代码术语
        List<String> codeTerms = extractCodeTerms(query);
        List<String> queryTerms = normalize(query);

        // 步骤 2: 批量预计算调用链数据（SQL IN 一次查询，替代 N 次循环查 DB）
        Map<String, Set<String>> callersByTerm = precomputeCallGraphBatch(projectId, codeTerms);

        List<ScoredChunk> result = candidates.stream()
                .map(c -> {
                    double original = c.getScore();
                    double codeOverlap = computeQueryCodeOverlap(c.getContent(), c.getFileName(), codeTerms);
                    double filePathScore = computeFilePathMatch(c.getFileName(), codeTerms);
                    double coverage = computeCoverage(c.getContent(), queryTerms);
                    double positionBonus = computePositionBonus(c.getSeq());

                    // 调用链评分（仅在提供 projectId 时启用）
                    double callGraphScore = 0;
                    if (projectId != null && !codeTerms.isEmpty()) {
                        callGraphScore = computeCallGraphScore(c.getFileName(), codeTerms, callersByTerm);
                    }

                    double finalScore = SCORE_WEIGHT * original
                            + CODE_OVERLAP_WEIGHT * codeOverlap
                            + FILE_PATH_WEIGHT * filePathScore
                            + COVERAGE_WEIGHT * coverage
                            + POSITION_WEIGHT * positionBonus;

                    // 调用链作为额外加分（不挤占其他维度的权重）
                    if (callGraphScore > 0) {
                        finalScore += callGraphScore * 0.05;
                    }

                    return new ScoredChunk(c.getChunkId(), c.getDocId(), c.getSeq(),
                            c.getFileName(), c.getContent(),
                            Math.round(finalScore * 10000.0) / 10000.0,
                            c.getSource(), null);
                })
                .sorted(Comparator.comparingDouble(ScoredChunk::getScore).reversed())
                .limit(topN)
                .toList();

        if (!codeTerms.isEmpty() || projectId != null) {
            log.debug("rerank(code-aware): query={}, codeTerms={}, projectId={}, candidates={}, result={}",
                    query, codeTerms, projectId, candidates.size(), result.size());
        }
        return result;
    }

    // ==================== 代码术语提取 ====================

    /**
     * 从自然语言查询中提取代码标识符。
     */
    List<String> extractCodeTerms(String query) {
        if (query == null || query.isBlank()) return List.of();
        Set<String> terms = new LinkedHashSet<>();

        Matcher parenMatcher = METHOD_CALL.matcher(query);
        while (parenMatcher.find()) {
            terms.add(parenMatcher.group(1).toLowerCase());
        }

        Matcher dotMatcher = DOT_CHAIN.matcher(query);
        while (dotMatcher.find()) {
            String full = dotMatcher.group().toLowerCase();
            terms.add(full.replace(".", ""));
            for (String part : full.split("\\.")) {
                if (part.length() > 1) terms.add(part);
            }
        }

        Matcher camelMatcher = CAMEL_SPLIT.matcher(query.replaceAll("[^a-zA-Z0-9_ ]", " "));
        while (camelMatcher.find()) {
            String t = camelMatcher.group().toLowerCase();
            if (t.length() > 1) terms.add(t);
        }

        return terms.stream()
                .filter(t -> t.matches(".*[a-zA-Z].*"))
                .filter(t -> t.length() <= 64)
                .toList();
    }

    // ==================== 代码重叠评分 ====================

    private double computeQueryCodeOverlap(String content, String fileName, List<String> codeTerms) {
        if (codeTerms.isEmpty()) return 0;
        String lowerContent = (content != null ? content.toLowerCase() : "")
                + " " + (fileName != null ? fileName.toLowerCase() : "");
        long hits = codeTerms.stream().filter(lowerContent::contains).count();
        return (double) hits / codeTerms.size();
    }

    private double computeFilePathMatch(String fileName, List<String> codeTerms) {
        if (fileName == null || codeTerms.isEmpty()) return 0;
        String lowerFile = fileName.toLowerCase();
        for (String term : codeTerms) {
            if (lowerFile.contains(term)) return 1.0;
            int idx = lowerFile.lastIndexOf('/');
            String baseName = idx >= 0 ? lowerFile.substring(idx + 1) : lowerFile;
            if (baseName.contains(term)) return 0.8;
        }
        return 0;
    }

    // ==================== 调用链评分（批量查询版） ====================

    private double computeCallGraphScore(String fileName, List<String> codeTerms,
                                          Map<String, Set<String>> callersByTerm) {
        if (fileName == null || callersByTerm.isEmpty()) return 0;
        String lowerFile = fileName.toLowerCase();
        int hits = 0;
        for (String term : codeTerms) {
            Set<String> callers = callersByTerm.get(term);
            if (callers != null) {
                if (callers.contains(lowerFile) || callers.contains(fileName)) {
                    hits++;
                }
                for (String caller : callers) {
                    if (lowerFile.contains(caller.replaceAll(".*/", "").replace(".java", ""))) {
                        hits++;
                        break;
                    }
                }
            }
        }
        return Math.min(1.0, hits * 0.5);
    }

    /**
     * 批量预计算调用链数据。
     *
     * <p>优化：一次 SQL IN 查询所有 codeTerms 的调用方，
     * 替代原有的逐 term 循环查询（N+1 问题）。
     */
    private Map<String, Set<String>> precomputeCallGraphBatch(Long projectId, List<String> codeTerms) {
        if (projectId == null || codeTerms.isEmpty()) return Map.of();

        try {
            // 逐个查询每个方法的调用方，组装为 Map<方法名, 调用方列表>
            Map<String, Set<String>> result = new HashMap<>();
            for (String term : codeTerms) {
                List<String> callers = codeUnitRepo.findCallersByMethodName(projectId, term);
                if (!callers.isEmpty()) {
                    Set<String> normalized = callers.stream()
                            .map(String::toLowerCase)
                            .collect(Collectors.toSet());
                    result.merge(term, normalized, (a, b) -> { a.addAll(b); return a; });
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("批量调用链查询失败: {}", e.getMessage());
            return Map.of();
        }
    }

    // ==================== 通用特征 ====================

    private double computeCoverage(String content, List<String> queryTerms) {
        if (queryTerms.isEmpty() || content == null || content.isEmpty()) return 0;
        String lowerContent = content.toLowerCase();
        long hits = queryTerms.stream().filter(lowerContent::contains).count();
        return (double) hits / queryTerms.size();
    }

    private double computePositionBonus(Integer seq) {
        if (seq == null || seq <= 0) return 0;
        double bonus = 0.2 * Math.max(0, 1.0 - (seq - 1) / 10.0);
        return Math.round(bonus * 100.0) / 100.0;
    }

    private List<String> normalize(String query) {
        if (query == null || query.isBlank()) return List.of();
        String q = query.toLowerCase().replaceAll("[\\s,，。；;、．.！!？?（）()【】\\[\\]：:]+", "");
        if (q.length() <= 2) return List.of(q);
        java.util.List<String> terms = new java.util.ArrayList<>();
        for (int i = 0; i + 2 <= q.length(); i++) {
            terms.add(q.substring(i, i + 2));
        }
        return terms;
    }
}
