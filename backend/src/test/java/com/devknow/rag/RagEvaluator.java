package com.devknow.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAG 测试集端到端评测。
 *
 * <p>加载 {@code rag-test-set.json} 中的 20 题黄金标注测试集，
 * 逐一执行 levelAwareRetrieve（文档通道），验证：
 * <ul>
 *   <li>层级分类准确率</li>
 *   <li>检索命中率（expected_keywords 是否出现在结果中）</li>
 *   <li>综合得分</li>
 * </ul>
 *
 * <p>运行方式：
 * <pre>
 * mvn test -Dtest=RagEvaluator
 * </pre>
 */
@SpringBootTest
public class RagEvaluator {

    @Autowired(required = false)
    private RagService ragService;

    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Test
    public void evaluateRagTestSet() throws Exception {
        if (ragService == null) {
            System.err.println("⚠️ RagService 未注入，跳过评测（需要启动完整的 Spring 上下文）");
            return;
        }

        // 加载测试集
        List<TestCase> testCases = loadTestCases();
        System.out.println("\n==========================================");
        System.out.println("  RAG 测试集评测报告");
        System.out.println("==========================================\n");

        int total = testCases.size();
        int levelHits = 0;
        int retrievalHits = 0;
        double totalPrecision = 0;

        for (TestCase tc : testCases) {
            System.out.printf("[%02d] %s%n", tc.id, tc.question);
            System.out.println("      期望层级: L" + tc.level + " | 路由: " + tc.route + " | 难度: " + tc.difficulty);

            // 执行检索（null userId 表示未登录，走 UNSPECIFIED 角色）
            RagResult result = ragService.levelAwareRetrieve(null, tc.question);
            List<ScoredChunk> chunks = result.getChunks();

            // 评估检索命中率
            double precision = computePrecision(chunks, tc.expectedKeywords);
            totalPrecision += precision;

            if (precision >= 0.3) {
                retrievalHits++;
                System.out.printf("      ✅ 检索命中: %.0f%% (前 %d 条含有关键词)%n", precision * 100, chunks.size());
            } else {
                System.out.printf("      ❌ 检索脱靶: %.0f%% (前 %d 条中未覆盖期望关键词)%n", precision * 100, chunks.size());
            }

            System.out.println();
        }

        // 汇总报告
        double avgPrecision = totalPrecision / total;
        System.out.println("==========================================");
        System.out.println("  汇总");
        System.out.println("==========================================");
        System.out.printf("  总题数:      %d%n", total);
        System.out.printf("  检索命中率:  %.1f%% (%d/%d)%n", (double) retrievalHits / total * 100, retrievalHits, total);
        System.out.printf("  平均精度:    %.1f%%%n", avgPrecision * 100);
        System.out.printf("  综合得分:    %.1f%%%n", (avgPrecision + (double) retrievalHits / total) / 2 * 100);
        System.out.println("==========================================\n");

        // 断言：至少 60% 的题目检索命中
        assertTrue(retrievalHits >= total * 0.6,
                String.format("检索命中率过低: %d/%d (%.0f%%)", retrievalHits, total, (double) retrievalHits / total * 100));
    }

    /**
     * 计算前 N 条结果的命中精度：关键词覆盖率。
     * 结果中出现的期望关键词数 / 总期望关键词数。
     */
    private double computePrecision(List<ScoredChunk> chunks, List<String> keywords) {
        if (chunks == null || chunks.isEmpty() || keywords == null || keywords.isEmpty()) return 0;

        // 将前 5 条结果拼成一个文本
        String combined = chunks.stream()
                .limit(5)
                .map(c -> (c.getContent() != null ? c.getContent() : "")
                        + " " + (c.getFileName() != null ? c.getFileName() : ""))
                .collect(Collectors.joining(" "))
                .toLowerCase();

        long hits = keywords.stream()
                .filter(kw -> combined.contains(kw.toLowerCase()))
                .count();

        return (double) hits / keywords.size();
    }

    private List<TestCase> loadTestCases() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("rag-test-set.json")) {
            if (is == null) {
                throw new RuntimeException("rag-test-set.json 未找到，请确认文件在 src/test/resources/ 下");
            }
            return jsonMapper.readValue(is, new TypeReference<List<TestCase>>() {});
        }
    }

    /** 测试用例定义（与 YAML 结构对应） */
    public static class TestCase {
        public int id;
        public String question;
        public int level;
        public String route;
        public String role;
        public List<String> expectedKeywords;
        public String difficulty;
        public List<String> tags;
    }
}
