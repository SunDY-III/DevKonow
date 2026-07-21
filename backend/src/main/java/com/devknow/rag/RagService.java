package com.devknow.rag;

import com.devknow.auth.RoleLevelMapper;
import com.devknow.auth.User;
import com.devknow.auth.UserKnowledgeRole;
import com.devknow.auth.UserRepository;
import com.devknow.config.rerank.LevelClassifier;
import com.devknow.config.rerank.LevelResult;
import com.devknow.governance.TokenAuditService;
import com.devknow.knowledge.DocumentChunk;
import com.devknow.knowledge.DocumentChunkRepository;
import com.devknow.knowledge.graph.GraphExpander;
import com.devknow.knowledge.graph.GraphRelationResult;
import com.devknow.knowledge.graph.KnowledgeGraphService;
import com.devknow.rag.strategy.ChunkStrategy;
import com.devknow.rag.strategy.RagStrategyRouter;
import com.devknow.vector.ScoredChunk;
import com.devknow.vector.VectorStoreService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 检索服务 —— 层级感知 + 策略驱动。
 *
 * <p>通过 {@link RagStrategyRouter} 按场景加载 {@link ChunkStrategy}，
 * 控制 HyDE / MMR / 检查点掩码 / Cross-encoder 权重 / 候选池大小等参数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    @Qualifier("docEmbeddingModel")
    private final EmbeddingModel embeddingModel;
    private final VectorStoreService vectorStoreService;
    private final DocumentChunkRepository chunkRepository;
    private final Reranker reranker;
    private final CrossEncoderReranker crossEncoderReranker;
    private final HydeGenerator hydeGenerator;
    private final CorrectiveEvaluator correctiveEvaluator;
    private final HallucinationGuard hallucinationGuard;
    private final MmrSelector mmrSelector;
    private final QueryExpander queryExpander;
    private final TokenAuditService tokenAuditService;
    private final GraphExpander graphExpander;
    private final KnowledgeGraphService knowledgeGraphService;
    private final LevelClassifier levelClassifier;
    private final RoleLevelMapper roleLevelMapper;
    private final UserRepository userRepository;
    private final RagStrategyRouter strategyRouter;

    @Value("${app.rag.vector-top-k}")  private int vectorTopK;
    @Value("${app.rag.keyword-top-k}") private int keywordTopK;
    @Value("${app.rag.rerank-top-n}")  private int rerankTopN;
    @Value("${app.rag.rrf-k}")         private int rrfK;

    private static final double MMR_LAMBDA = 0.7;
    private static final int MMR_CANDIDATE_POOL = 12;

    /**
     * 根据层级和置信度计算自适应 MMR λ。
     *
     * <p>λ 越大越偏相关性，越小越偏多样性：
     * <ul>
     *   <li>L1（战略/架构）→ 0.35，高多样性覆盖全局</li>
     *   <li>L2（系统设计）→ 0.50，平衡偏多样</li>
     *   <li>L3（模块逻辑）→ 0.60，平衡偏相关</li>
     *   <li>L4（实现细节）→ 0.75，相关优先</li>
     *   <li>L5（具体代码行）→ 0.85，强相关精准定位</li>
     * </ul>
     * 置信度二次调节：低置信度（<0.5）降 λ 扩多样性，高置信度（>0.8）升 λ 聚焦相关。
     */
    private double computeAdaptiveLambda(int targetLevel, double confidence) {
        double baseLambda;
        switch (targetLevel) {
            case 1:  baseLambda = 0.35; break;
            case 2:  baseLambda = 0.50; break;
            case 3:  baseLambda = 0.60; break;
            case 4:  baseLambda = 0.75; break;
            case 5:  baseLambda = 0.85; break;
            default: baseLambda = 0.60;
        }
        if (confidence < 0.5) {
            baseLambda -= 0.10;
        } else if (confidence > 0.8) {
            baseLambda += 0.10;
        }
        return Math.max(0.3, Math.min(0.95, baseLambda));
    }

    /**
     * 构建图谱文档关联关系（含跳数 hops）。
     * 返回 Map<docId, Map<relatedDocId, hops>>，用于图感知 MMR 的 α 衰减。
     */
    private Map<Long, Map<Long, Integer>> buildGraphRelationMap(List<ScoredChunk> candidates, int maxHops) {
        Map<Long, Map<Long, Integer>> relationMap = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        for (ScoredChunk c : candidates) {
            Long docId = c.getDocId();
            if (docId == null || !seen.add(docId)) continue;
            try {
                List<GraphRelationResult> related = knowledgeGraphService.findRelated(docId, maxHops);
                if (!related.isEmpty()) {
                    Map<Long, Integer> relatedWithHops = new HashMap<>();
                    for (GraphRelationResult r : related) {
                        Long relatedDocId = r.getDocId();
                        int hops = r.getHops();
                        relatedWithHops.merge(relatedDocId, hops, Math::min);
                    }
                    relationMap.put(docId, relatedWithHops);
                }
            } catch (Exception e) {
                log.debug("图谱关联查询失败 docId={}: {}", docId, e.getMessage());
            }
        }
        return relationMap;
    }

    // ==================== 公共接口 ====================

    public float[] embed(Long userId, String text) {
        float[] v = embeddingModel.embed(text).content().vector();
        tokenAuditService.record(userId, "EMBEDDING", text.length() / 2, 0);
        return v;
    }

    public List<ScoredChunk> retrieveCode(Long userId, String question) {
        return retrieveCode(userId, null, question);
    }

    public List<ScoredChunk> retrieveCode(Long userId, Long projectId, String question) {
        float[] queryVector = embed(userId, question);
        String codePrefix = projectId != null
                ? "vec:" + projectId + ":code:*"
                : "vec:0:code:*";
        List<ScoredChunk> results = vectorStoreService.searchByPrefix(codePrefix, queryVector, vectorTopK);
        log.info("rag retrieveCode: q={}, projectId={}, hits={}", question, projectId, results.size());
        return results;
    }

    public String buildContext(List<ScoredChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            ScoredChunk c = chunks.get(i);
            sb.append("[片段").append(i + 1).append(" 来源:").append(c.getFileName())
              .append(" #").append(c.getSeq()).append("]\n")
              .append(c.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    // ==================== 层级感知检索（策略驱动） ====================

    /**
     * 层级感知检索，使用默认场景（"doc" 精准模式）。
     */
    public RagResult levelAwareRetrieve(Long userId, String question) {
        return levelAwareRetrieve(userId, null, question, "doc");
    }

    /**
     * 层级感知检索，使用默认场景（"doc" 精准模式）。
     */
    public RagResult levelAwareRetrieve(Long userId, Long projectId, String question) {
        return levelAwareRetrieve(userId, projectId, question, "doc");
    }

    /**
     * 层级感知检索，按场景策略路由参数。
     *
     * <p>场景策略控制：
     * <ul>
     *   <li>HyDE 是否启用（学习场景关闭）</li>
     *   <li>MMR 是否启用（代码场景关闭）</li>
     *   <li>候选池大小（学习场景扩大 2 倍）</li>
     *   <li>Cross-encoder 融合权重（学习场景 0.5，默认 0.7）</li>
     *   <li>检查点跳过掩码（学习场景跳过 C1+C2）</li>
     *   <li>向量 TopK / 关键词 TopK</li>
     * </ul>
     *
     * @param userId    用户 ID
     * @param projectId 项目 ID（可为 null）
     * @param question  用户问题
     * @param scenario  场景名称（learn / interview / safety / code / doc / default）
     */
    public RagResult levelAwareRetrieve(Long userId, Long projectId, String question, String scenario) {
        long startNanos = System.nanoTime();
        ChunkStrategy strategy = strategyRouter.resolve(scenario);

        UserKnowledgeRole userRole = getUserKnowledgeRole(userId);

        LevelResult levelResult = levelClassifier.classify(question);
        int targetLevel = levelResult.getLevel();
        double llmConfidence = levelResult.getConfidence();

        RoleLevelMapper.AdjustedPlan plan = roleLevelMapper.adjust(userRole, targetLevel, llmConfidence);
        int[] searchLevels = plan.getSearchLevels();
        double confidence = plan.getAdjustedConfidence();
        boolean needRouteB = plan.isNeedRouteB();

        int effectiveVectorTopK = strategy.getVectorTopK();
        int effectiveKeywordTopK = strategy.getKeywordTopK();

        // ========== 步骤 1: HyDE ==========
        String hydeQuery;
        if (strategy.isHydeEnabled()) {
            hydeQuery = hydeGenerator.generateHypothesis(question);
        } else {
            hydeQuery = question;
            log.debug("HyDE 已禁用（scenario={}），使用原始查询", scenario);
        }

        // ========== 步骤 2: 向量搜索 ==========
        float[] queryVector = embed(userId, hydeQuery);
        List<ScoredChunk> vectorHits = new ArrayList<>(vectorStoreService.searchByLevels(queryVector, effectiveVectorTopK, searchLevels));
        for (ScoredChunk c : vectorHits) c.setSource("");

        // ========== 步骤 3: 角色补刀（B 路） ==========
        if (needRouteB) {
            List<ScoredChunk> backupHits = vectorStoreService.searchByLevels(queryVector, effectiveVectorTopK,
                    new int[]{targetLevel});
            for (ScoredChunk c : backupHits) {
                c.setScore(c.getScore() * 0.6);
                c.setSource("B");
            }
            Set<Long> seenIds = new HashSet<>();
            for (ScoredChunk c : vectorHits) seenIds.add(c.getChunkId());
            for (ScoredChunk c : backupHits) {
                if (seenIds.add(c.getChunkId())) vectorHits.add(c);
            }
        }

        // ========== 步骤 4: 关键词搜索 + 同义词扩展 ==========
        List<String> expandedTerms = queryExpander.expand(question);
        String expandedQuery = String.join(" ", expandedTerms);

        List<Integer> levelList = new ArrayList<>();
        for (int l : searchLevels) levelList.add(l);
        List<DocumentChunk> kwRaw = chunkRepository.keywordSearchByLevel(expandedQuery, levelList, effectiveKeywordTopK);
        List<ScoredChunk> keywordHits = kwRaw.stream()
                .map(c -> new ScoredChunk(c.getId(), c.getDocId(), c.getSeq(), "", c.getContent(), 0.0, "keyword"))
                .toList();

        // ========== 步骤 5: RRF 融合 ==========
        List<ScoredChunk> fused = RrfFusion.fuse(vectorHits, keywordHits, rrfK);

        // ========== 步骤 6: MMR 多样性去重（可配置开关和候选池大小） ==========
        int candidatePoolSize = strategy.candidatePoolSize();
        List<ScoredChunk> candidates = fused.size() > candidatePoolSize
                ? fused.subList(0, candidatePoolSize)
                : fused;

        Map<String, float[]> vectorMap = vectorStoreService.retrieveVectors(
                candidates.stream().map(ScoredChunk::getChunkId).collect(Collectors.toList()));

        List<ScoredChunk> diverse;
        if (strategy.isMmrEnabled()) {
            double adaptiveLambda = computeAdaptiveLambda(targetLevel, confidence);
            Map<Long, Map<Long, Integer>> graphRelationMap = buildGraphRelationMap(candidates, 2);
            diverse = mmrSelector.select(
                    queryVector, candidates,
                    (docId, chunkId) -> vectorMap.get(String.valueOf(chunkId)),
                    adaptiveLambda, strategy.getRerankTopN(), graphRelationMap);
        } else {
            // MMR 关闭时直接取 TopN
            diverse = candidates.stream()
                    .sorted(Comparator.comparingDouble(ScoredChunk::getScore).reversed())
                    .limit(strategy.getRerankTopN())
                    .toList();
            log.debug("MMR 已禁用（scenario={}），按原始分数取 TopN={}", scenario, strategy.getRerankTopN());
        }

        // ========== 步骤 7: 重排 + Cross-encoder + 图谱扩展 ==========
        List<ScoredChunk> topN = reranker.rerank(question, diverse, strategy.getRerankTopN(), projectId);
        List<ScoredChunk> ceRanked = crossEncoderReranker.rerank(
                question, topN, strategy.getRerankTopN(), strategy.getCrossEncoderWeight());
        List<ScoredChunk> expanded = graphExpander.expand(ceRanked, 3, 2);

        // ========== 步骤 8: CRAG 纠错评估 ==========
        confidence = calculateConfidence(vectorHits, keywordHits, fused, confidence);
        CorrectiveEvaluator.EvaluationResult cragResult = correctiveEvaluator.evaluate(
                expanded, question,
                originalResults -> {
                    int[] broaderLevels = new int[]{1, 2, 3, 4, 5};
                    float[] retryVector = embed(userId, question);
                    List<ScoredChunk> retryHits = vectorStoreService.searchByLevels(retryVector, effectiveVectorTopK * 2, broaderLevels);
                    if (!retryHits.isEmpty()) {
                        for (ScoredChunk c : retryHits) c.setSource("retry");
                    }
                    return retryHits;
                });

        List<ScoredChunk> cragChunks = cragResult.verdict() == CorrectiveEvaluator.EvaluationVerdict.LOW_CONFIDENCE
                ? List.of()
                : cragResult.chunks();

        // ========== 步骤 9: 幻觉第一关（策略感知检查点掩码） ==========
        List<ScoredChunk> finalChunks;
        if (!cragChunks.isEmpty()) {
            finalChunks = hallucinationGuard.executeCheckpoint1(
                    question, cragChunks, confidence, strategy.getSkipCheckpoints());
        } else {
            finalChunks = cragChunks;
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("levelAwareRetrieve: q={}, scenario={}, role={}, level={}, conf={}, "
                        + "vector={}, keyword={}, HyDE={}, MMR={}, pool={}, rerankTopN={}, "
                        + "CEweight={}, skipCheckpoints={}, final={}, elapsed={}ms",
                question, scenario, userRole, targetLevel, String.format("%.2f", confidence),
                vectorHits.size(), keywordHits.size(),
                strategy.isHydeEnabled(), strategy.isMmrEnabled(),
                candidatePoolSize, strategy.getRerankTopN(),
                strategy.getCrossEncoderWeight(), strategy.getSkipCheckpoints(),
                finalChunks.size(), elapsedMs);

        return new RagResult(finalChunks, confidence);
    }

    /**
     * 多因子置信度计算。
     *
     * <p>因子：
     * <ul>
     *   <li>最高分（余弦相似度，量纲稳定）</li>
     *   <li>分差因子（第 1 名 vs 第 2 名的差距，差距越大越可信）</li>
     *   <li>关键词确认率（向量命中的文档是否也在关键词通道出现）</li>
     * </ul>
     */
    private double calculateConfidence(List<ScoredChunk> vectorHits,
                                        List<ScoredChunk> keywordHits,
                                        List<ScoredChunk> fused,
                                        double roleAdjusted) {
        if (vectorHits.isEmpty() && keywordHits.isEmpty()) return 0;

        double topScore = 0;
        if (!vectorHits.isEmpty()) {
            topScore = vectorHits.get(0).getScore();
        } else if (!keywordHits.isEmpty()) {
            topScore = 0.5;
        }

        double gapFactor = 1.0;
        if (vectorHits.size() >= 2) {
            double gap = vectorHits.get(0).getScore() - vectorHits.get(1).getScore();
            gapFactor = Math.min(1.2, 1.0 + gap * 2);
        }

        double confirmRate = 1.0;
        if (!vectorHits.isEmpty() && !keywordHits.isEmpty()) {
            Set<Long> kwIds = keywordHits.stream()
                    .map(ScoredChunk::getDocId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            if (!kwIds.isEmpty()) {
                long confirmed = vectorHits.stream()
                        .filter(c -> kwIds.contains(c.getDocId()))
                        .count();
                confirmRate = 0.5 + 0.5 * (double) confirmed / vectorHits.size();
            }
        }

        double finalConf = topScore * gapFactor * confirmRate;
        finalConf = Math.min(1.0, finalConf * 0.8 + roleAdjusted * 0.2);

        return Math.round(finalConf * 10000.0) / 10000.0;
    }

    private UserKnowledgeRole getUserKnowledgeRole(Long userId) {
        if (userId == null) return UserKnowledgeRole.UNSPECIFIED;
        try {
            Optional<User> user = userRepository.findById(userId);
            if (user.isPresent() && user.get().getKnowledgeRole() != null) {
                String roleStr = user.get().getKnowledgeRole();
                if (!roleStr.isEmpty()) {
                    try {
                        return UserKnowledgeRole.valueOf(roleStr);
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        } catch (Exception e) {
            log.warn("获取用户角色失败（userId={}）: {}", userId, e.getMessage());
        }
        return UserKnowledgeRole.UNSPECIFIED;
    }
}
