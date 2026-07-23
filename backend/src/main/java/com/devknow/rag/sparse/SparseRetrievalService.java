package com.devknow.rag.sparse;

import com.devknow.knowledge.DocumentChunk;
import com.devknow.knowledge.DocumentChunkRepository;
import com.devknow.vector.ScoredChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 稀疏检索服务 —— 第三路检索通道。
 *
 * <p>流程：
 * <ol>
 *   <li>{@link SparseEncoder} 将查询拆解为带权词表 (term, weight)</li>
 *   <li>对每个高权重 term，通过 MySQL ngram FULLTEXT 索引召回匹配文档</li>
 *   <li>按 term weight × TF 因子加权合并结果</li>
 *   <li>返回 {@code ScoredChunk} 列表，source="sparse"</li>
 * </ol>
 *
 * <p>与现有关键词搜索的区别：
 * <ul>
 *   <li>现有关键词：所有词等权重，RRF 时只看排名不看分数</li>
 *   <li>稀疏检索：LLM 识别的核心词权重高，边缘词权重低，RRF 时额外携带权重信号</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SparseRetrievalService {

    private final SparseEncoder sparseEncoder;
    private final DocumentChunkRepository chunkRepository;

    private static final double TERM_WEIGHT_THRESHOLD = 0.3;
    private static final int TERM_RESULT_LIMIT = 15;
    private static final int FINAL_LIMIT = 30;

    /**
     * 执行稀疏检索。
     *
     * @param query  用户查询
     * @param levels 知识层级过滤（null=不限）
     * @return 按综合得分降序的检索结果
     */
    public List<ScoredChunk> search(String query, int[] levels) {
        if (query == null || query.isBlank()) return List.of();

        // 1. 稀疏编码：提取带权术语
        SparseVector sparse = sparseEncoder.encode(query);
        List<SparseVector.Entry> terms = sparse.getEntries();
        if (terms.isEmpty()) return List.of();

        // 2. 过滤低权重 term
        List<SparseVector.Entry> highWeightTerms = terms.stream()
                .filter(e -> e.getWeight() >= TERM_WEIGHT_THRESHOLD)
                .sorted(Comparator.<SparseVector.Entry>comparingDouble(e -> e.getWeight()).reversed())
                .collect(Collectors.toList());
        if (highWeightTerms.isEmpty()) return List.of();

        long start = System.nanoTime();

        // 3. 对每个高权重 term 执行 MySQL ngram 搜索，用 term weight 加权
        Map<Long, ScoredChunk> resultMap = new LinkedHashMap<>();
        List<Integer> levelList = levels != null && levels.length > 0
                ? Arrays.stream(levels).boxed().collect(Collectors.toList())
                : null;

        for (SparseVector.Entry term : highWeightTerms) {
            try {
                List<DocumentChunk> chunks;
                if (levelList != null && !levelList.isEmpty()) {
                    chunks = chunkRepository.keywordSearchByLevel(term.getTerm(), levelList, TERM_RESULT_LIMIT);
                } else {
                    chunks = chunkRepository.keywordSearch(term.getTerm(), TERM_RESULT_LIMIT);
                }

                if (chunks == null || chunks.isEmpty()) continue;

                for (DocumentChunk c : chunks) {
                    if (c.getId() == null || c.getDocId() == null) continue;

                    double tf = computeTf(c.getContent(), term.getTerm());
                    double score = term.getWeight() * tf;

                    resultMap.merge(c.getId(),
                            new ScoredChunk(c.getId(), c.getDocId(), c.getSeq(),
                                    "", c.getContent(), score, "sparse", null),
                            (a, b) -> {
                                a.setScore(a.getScore() + b.getScore());
                                return a;
                            });
                }
            } catch (Exception e) {
                log.warn("Sparse search term '{}' failed: {}", term.getTerm(), e.getMessage());
            }
        }

        // 4. 按总分排序 + 截断
        List<ScoredChunk> results = resultMap.values().stream()
                .sorted(Comparator.<ScoredChunk>comparingDouble(c -> c.getScore()).reversed())
                .limit(FINAL_LIMIT)
                .collect(Collectors.toList());

        long elapsed = (System.nanoTime() - start) / 1_000_000;
        log.info("SparseRetrieval: q={}, terms={}, hits={}, 耗时={}ms",
                truncate(query, 40),
                highWeightTerms.stream().map(SparseVector.Entry::getTerm).collect(Collectors.joining(",")),
                results.size(), elapsed);

        return results;
    }

    /**
     * TF 因子：计算 term 在文本中的出现频率（0~1）。
     */
    private double computeTf(String content, String term) {
        if (content == null || term == null || term.isBlank()) return 0.1;
        String lowerContent = content.toLowerCase();
        String lowerTerm = term.toLowerCase();

        int count = 0;
        int idx = 0;
        int termLen = lowerTerm.length();
        while ((idx = lowerContent.indexOf(lowerTerm, idx)) != -1) {
            count++;
            idx += termLen;
        }

        if (count == 0) return 0.1; // ngram 模糊匹配可能命中同源词
        return Math.min(1.0, 0.2 + 0.8 * (count / 5.0));
    }

    private static String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : (s != null ? s : "");
    }
}
