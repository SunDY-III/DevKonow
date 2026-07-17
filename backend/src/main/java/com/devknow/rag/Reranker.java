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
 * <p>代码术语提取策略：
 * <ul>
 *   <li>CamelCase 拆分（createOrder → create + order）</li>
 *   <li>点号调用链（service.createOrder → service + create + order）</li>
 *   <li>括号方法调用（createOrder(args) → createOrder）</li>
 * </ul>
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

        // 步骤 2: 预计算调用链数据（避免在流中反复查 DB）
        Map<String, Set<String>> callersByTerm = precomputeCallGraph(projectId, codeTerms);

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
                            c.getSource());
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
     * <p>
     * 覆盖三种常见写法：
     * <ul>
     *   <li>CamelCase: "createOrder" → ["create", "order"]</li>
     *   <li>调用链: "orderService.createOrder" → ["orderservice", "createorder", "service", "create", "order"]</li>
     *   <li>方法调用: "createOrder()" → ["createorder"]</li>
     * </ul>
     */
    List<String> extractCodeTerms(String query) {
        if (query == null || query.isBlank()) return List.of();
        Set<String> terms = new LinkedHashSet<>();

        // 1. 括号方法调用：createOrder(args) → "createorder"
        Matcher parenMatcher = METHOD_CALL.matcher(query);
        while (parenMatcher.find()) {
            terms.add(parenMatcher.group(1).toLowerCase());
        }

        // 2. 点号调用链：a.b.c → abc 以及 a, b, c
        Matcher dotMatcher = DOT_CHAIN.matcher(query);
        while (dotMatcher.find()) {
            String full = dotMatcher.group().toLowerCase();
            terms.add(full.replace(".", ""));
            for (String part : full.split("\\.")) {
                if (part.length() > 1) terms.add(part);
            }
        }

        // 3. CamelCase 拆分：createOrder → create + order
        //    (remove matched paren words to avoid noise)
        Matcher camelMatcher = CAMEL_SPLIT.matcher(query.replaceAll("[^a-zA-Z0-9_ ]", " "));
        while (camelMatcher.find()) {
            String t = camelMatcher.group().toLowerCase();
            if (t.length() > 1) terms.add(t);
        }

        // 过滤：去掉纯中文词（不包含 ASCII 字母的）
        return terms.stream()
                .filter(t -> t.matches(".*[a-zA-Z].*"))
                .filter(t -> t.length() <= 64)
                .toList();
    }

    // ==================== 代码重叠评分 ====================

    /**
     * 查询中的代码术语在 chunk 内容和文件名中的命中率。
     * <p>
     * 如 query="createOrder 限流策略"，codeTerms=["create", "order", "createorder"],
     * chunk="...createOrder()..." → 命中 3/3 = 1.0
     */
    private double computeQueryCodeOverlap(String content, String fileName, List<String> codeTerms) {
        if (codeTerms.isEmpty()) return 0;
        String lowerContent = (content != null ? content.toLowerCase() : "")
                + " " + (fileName != null ? fileName.toLowerCase() : "");
        long hits = codeTerms.stream().filter(lowerContent::contains).count();
        return (double) hits / codeTerms.size();
    }

    /**
     * 文件路径匹配度。
     * <p>
     * 如果 chunk 的文件路径中包含代码术语中的任意一个，说明该 chunk 很可能是用户要找的
     * 类/方法所在的文件。如 query 含 "OrderService"，chunk 在 "service/OrderService.java" 中 → 高分。
     */
    private double computeFilePathMatch(String fileName, List<String> codeTerms) {
        if (fileName == null || codeTerms.isEmpty()) return 0;
        String lowerFile = fileName.toLowerCase();
        for (String term : codeTerms) {
            // 精确匹配文件/类名
            if (lowerFile.contains(term)) return 1.0;
            // 文件名前缀匹配（如 OrderService 匹配 OrderServiceImpl）
            int idx = lowerFile.lastIndexOf('/');
            String baseName = idx >= 0 ? lowerFile.substring(idx + 1) : lowerFile;
            if (baseName.contains(term)) return 0.8;
        }
        return 0;
    }

    // ==================== 调用链评分 ====================

    /**
     * 调用链相关性。
     * <p>
     * 检查 query 中的代码术语是否在本 chunk 的方法调用链中出现。
     * 如 query="createOrder"，chunk 所在的文件调用了 createOrder → 该 chunk 对理解调用链有价值。
     */
    private double computeCallGraphScore(String fileName, List<String> codeTerms,
                                          Map<String, Set<String>> callersByTerm) {
        if (fileName == null || callersByTerm.isEmpty()) return 0;
        String lowerFile = fileName.toLowerCase();
        int hits = 0;
        for (String term : codeTerms) {
            Set<String> callers = callersByTerm.get(term);
            if (callers != null) {
                // 本文件是调用方 → chunk 解释了"为什么调用"
                if (callers.contains(lowerFile) || callers.contains(fileName)) {
                    hits++;
                }
                // 本文件被同一调用链中的其他文件调用
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
     * 预计算调用链数据：每个代码术语 → 调用方文件路径集合。
     */
    private Map<String, Set<String>> precomputeCallGraph(Long projectId, List<String> codeTerms) {
        if (projectId == null || codeTerms.isEmpty()) return Map.of();
        Map<String, Set<String>> result = new HashMap<>();
        for (String term : codeTerms) {
            try {
                List<String> callers = codeUnitRepo.findCallersByMethodName(projectId, term);
                if (!callers.isEmpty()) {
                    Set<String> normalized = callers.stream()
                            .map(String::toLowerCase)
                            .collect(Collectors.toSet());
                    result.put(term, normalized);
                }
            } catch (Exception e) {
                log.debug("调用链查询失败 term={}: {}", term, e.getMessage());
            }
        }
        return result;
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
