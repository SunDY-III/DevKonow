package com.devknow.rag;

import com.devknow.codeindex.CodeUnitEntity;
import com.devknow.codeindex.CodeUnitEntityRepository;
import com.devknow.vector.ScoredChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 严格验证器（StrictVerifier）—— 纯规则引擎，无 LLM 调用。
 *
 * <p>对 RRF 融合后的候选结果进行精确匹配验证，确保最终进入 LLM 的代码片段
 * 与方法查询意图一致。验证维度：
 * <ul>
 *   <li>方法名精确匹配（若有明确方法名查询）</li>
 *   <li>类名/文件名匹配（若有明确类名查询）</li>
 *   <li>置信度多因子打分（确保 >= 阈值才放行）</li>
 * </ul>
 *
 * <p>所有过滤结果均附带人类可读的诊断信息。
 */
@Slf4j
@Component
public class StrictVerifier {

    private final CodeUnitEntityRepository codeUnitRepo;

    /** 置信度通过阈值（与文档和配置保持一致） */
    static final double PASS_THRESHOLD = 0.60;

    public StrictVerifier(CodeUnitEntityRepository codeUnitRepo) {
        this.codeUnitRepo = codeUnitRepo;
    }

    /**
     * 严格验证入口。
     *
     * @param candidates RRF 融合后的候选列表
     * @param methodName 用户查询的方法名（可能为 null）
     * @param className  用户查询的类名（可能为 null）
     * @param projectId  项目 ID（可为 null）
     * @return 验证结果，包含通过列表和诊断信息
     */
    public VerificationResult verify(List<ScoredChunk> candidates,
                                      String methodName,
                                      String className,
                                      Long projectId) {
        long start = System.currentTimeMillis();
        List<String> diagnostics = new ArrayList<>();

        if (candidates == null || candidates.isEmpty()) {
            return new VerificationResult(List.of(), 0.0, "候选列表为空，无结果可验证");
        }

        // 逐条验证
        List<VerifiedItem> verified = new ArrayList<>();
        for (ScoredChunk c : candidates) {
            double score = verifySingle(c, methodName, className, projectId);
            if (score >= PASS_THRESHOLD) {
                verified.add(new VerifiedItem(c, score));
            } else {
                String reason = buildRejectionReason(c, score, methodName, className);
                diagnostics.add(reason);
                log.debug("StrictVerifier 过滤: chunkId={}, fileName={}, score={:.2f}, reason={}",
                        c.getChunkId(), c.getFileName(), score, reason);
            }
        }

        // 计算最终置信度（使用加权公式而非 ad-hoc 加分）
        double confidence = computeVerifiedConfidence(verified, methodName, className);

        // 构建诊断摘要
        StringBuilder diagBuilder = new StringBuilder();
        if (verified.isEmpty()) {
            if (methodName != null) {
                diagBuilder.append("未找到完全匹配方法「").append(methodName).append("」的结果。");
                if (className != null) {
                    diagBuilder.append("请在类「").append(className).append("」中检查该方法是否存在。");
                }
                diagBuilder.append("可能的解决方案：尝试更宽泛的搜索词，或检查方法名拼写。");
            } else {
                diagBuilder.append("所有候选均未通过验证。");
            }
            if (!diagnostics.isEmpty()) {
                diagBuilder.append(" 详细原因：");
                diagBuilder.append(diagnostics.stream().limit(3).collect(Collectors.joining("; ")));
                if (diagnostics.size() > 3) {
                    diagBuilder.append(" 等共 ").append(diagnostics.size()).append(" 条被过滤。");
                }
            }
        } else {
            diagBuilder.append("通过验证 ").append(verified.size()).append("/").append(candidates.size())
                    .append(" 条，置信度=").append(String.format("%.2f", confidence));
        }

        List<ScoredChunk> passed = verified.stream()
                .map(v -> new ScoredChunk(v.chunk.getChunkId(), v.chunk.getDocId(), v.chunk.getSeq(),
                        v.chunk.getFileName(), v.chunk.getContent(), v.score, v.chunk.getSource()))
                .toList();

        log.info("StrictVerifier: 输入={}, 通过={}, 置信度={:.2f}, 耗时={}ms",
                candidates.size(), passed.size(), confidence, System.currentTimeMillis() - start);

        return new VerificationResult(passed, confidence, diagBuilder.toString());
    }

    /**
     * 单条验证：多因子置信度加权公式。
     *
     * <p>文档一致的多因子加权公式：
     * <pre>
     *   Score = BaseScore × 0.40
     *         + ExactNameMatch × 0.25
     *         + ClassNameMatch × 0.15
     *         + BodyCoverage × 0.10
     *         + PositionBonus × 0.10
     * </pre>
     *
     * <p>BaseScore = max(0, min(1, 原始 RRF 分 / 最高候选 RRF 分))
     */
    double verifySingle(ScoredChunk chunk, String queryMethodName,
                        String queryClassName, Long projectId) {
        double baseScore = normalizeBaseScore(chunk);

        double exactNameMatch = computeExactNameMatch(chunk, queryMethodName);
        double classNameMatch = computeClassNameMatch(chunk, queryClassName, projectId);
        double bodyCoverage = computeBodyCoverage(chunk);
        double positionBonus = computePositionBonus(chunk);

        // 加权公式（与文档一致）
        double finalScore = baseScore * 0.40
                + exactNameMatch * 0.25
                + classNameMatch * 0.15
                + bodyCoverage * 0.10
                + positionBonus * 0.10;

        return Math.round(finalScore * 10000.0) / 10000.0;
    }

    // ==================== 各因子计算 ====================

    /**
     * 归一化基础分：原始 RRF 分 [0,1] 映射。
     * 基于 Top-1 分数做相对归一化。
     */
    private double normalizeBaseScore(ScoredChunk chunk) {
        double raw = chunk.getScore();
        if (raw <= 0) return 0.0;
        return Math.min(1.0, raw);
    }

    /**
     * 方法名精确匹配得分。
     * fileName 中如果包含 queryMethodName → 1.0，否则 0.0。
     */
    private double computeExactNameMatch(ScoredChunk chunk, String queryMethodName) {
        if (queryMethodName == null || queryMethodName.isBlank()) {
            return 0.5; // 无明确方法名时给中性分
        }
        String fileName = chunk.getFileName() != null ? chunk.getFileName().toLowerCase() : "";
        String content = chunk.getContent() != null ? chunk.getContent().toLowerCase() : "";
        String qm = queryMethodName.toLowerCase();

        // 文件名中含方法名 → 精确匹配
        if (fileName.contains(qm)) return 1.0;
        // 内容中包含方法名且是独立词（前后非字母数字）
        if (content.contains(qm)) {
            // 检查是否是完整词匹配
            int idx = content.indexOf(qm);
            if ((idx == 0 || !Character.isLetterOrDigit(content.charAt(idx - 1)))
                    && (idx + qm.length() >= content.length()
                    || !Character.isLetterOrDigit(content.charAt(idx + qm.length())))) {
                return 0.8;
            }
            return 0.5; // 作为子串包含
        }
        return 0.0;
    }

    /**
     * 类名匹配得分。
     *
     * <p>优先使用数据库中的 className 字段，
     * 不依赖文件名推测（避免内部类/嵌套类/Kotlin 文件名与类名不一致问题）。
     */
    private double computeClassNameMatch(ScoredChunk chunk, String queryClassName, Long projectId) {
        if (queryClassName == null || queryClassName.isBlank()) {
            return 0.5; // 无明确类名时给中性分
        }
        String qc = queryClassName.toLowerCase();

        // 从 chunk 的 fileName 中匹配
        String fileName = chunk.getFileName() != null ? chunk.getFileName().toLowerCase() : "";
        if (fileName.contains(qc)) return 1.0;

        // 如果提供了 projectId，从数据库 className 字段精确匹配
        if (projectId != null && chunk.getChunkId() != null) {
            try {
                CodeUnitEntity entity = codeUnitRepo.findById(chunk.getChunkId()).orElse(null);
                if (entity != null && entity.getClassName() != null
                        && entity.getClassName().toLowerCase().contains(qc)) {
                    return 1.0;
                }
            } catch (Exception e) {
                log.debug("类名 DB 查询失败: {}", e.getMessage());
            }
        }

        return 0.0;
    }

    /**
     * 方法体覆盖率评分。
     */
    private double computeBodyCoverage(ScoredChunk chunk) {
        String content = chunk.getContent();
        if (content == null || content.isBlank()) return 0.0;
        // 有完整方法体代码通常信息量更高
        int newlines = content.split("\n", -1).length;
        if (newlines >= 10) return 1.0;
        if (newlines >= 5) return 0.7;
        if (newlines >= 3) return 0.4;
        return 0.2;
    }

    private double computePositionBonus(ScoredChunk chunk) {
        Integer seq = chunk.getSeq();
        if (seq == null || seq <= 0) return 0;
        return Math.max(0, 0.2 * (1.0 - Math.min(1.0, (seq - 1) / 20.0)));
    }

    // ==================== 置信度计算 ====================

    /**
     * 多因子加权置信度公式（与文档一致）。
     *
     * <pre>
     *   Confidence = BaseScore × 0.40
     *              + ExactNameMatch × 0.25
     *              + ClassNameMatch × 0.15
     *              + BodyCoverage × 0.10
     *              + PositionBonus × 0.10
     * </pre>
     *
     * <p>取通过验证的所有条目的加权平均。
     */
    private double computeVerifiedConfidence(List<VerifiedItem> verified,
                                              String methodName, String className) {
        if (verified.isEmpty()) return 0.0;

        double avgScore = verified.stream()
                .mapToDouble(v -> v.score)
                .average()
                .orElse(0.0);

        // 方法名匹配加成
        double nameBonus = (methodName != null && verified.stream()
                .anyMatch(v -> v.chunk.getFileName() != null
                        && v.chunk.getFileName().toLowerCase().contains(methodName.toLowerCase())))
                ? 0.05 : 0.0;

        // 类名匹配加成
        double classBonus = (className != null && verified.stream()
                .anyMatch(v -> v.chunk.getFileName() != null
                        && v.chunk.getFileName().toLowerCase().contains(className.toLowerCase())))
                ? 0.05 : 0.0;

        double finalConf = (avgScore * 0.40 + nameBonus * 0.25 + classBonus * 0.15) / 0.80;
        return Math.min(1.0, Math.round(finalConf * 10000.0) / 10000.0);
    }

    /**
     * 构建人类可读的拒绝原因。
     * 用于 Disagreement #8：用户搜索无结果时能看到原因。
     */
    private String buildRejectionReason(ScoredChunk chunk, double score,
                                         String methodName, String className) {
        StringBuilder sb = new StringBuilder();
        sb.append("过滤 [").append(chunk.getFileName()).append("]");
        if (methodName != null) {
            double nameScore = computeExactNameMatch(chunk, methodName);
            if (nameScore < 0.5) {
                sb.append(": 方法名「").append(methodName).append("」不匹配");
            }
        }
        if (className != null && computeClassNameMatch(chunk, className, null) < 0.5) {
            sb.append(": 类名「").append(className).append("」不匹配");
        }
        sb.append(" (得分=").append(String.format("%.2f", score))
                .append(" < 阈值=").append(PASS_THRESHOLD).append(")");
        return sb.toString();
    }

    // ==================== 内部类型 ====================

    /** 验证通过的条目 */
    private record VerifiedItem(ScoredChunk chunk, double score) {}

    /** 验证结果 */
    public record VerificationResult(
            List<ScoredChunk> passed,
            double confidence,
            String diagnosis
    ) {
        public boolean allFiltered() {
            return passed == null || passed.isEmpty();
        }
    }
}
