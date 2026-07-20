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
import com.devknow.vector.ScoredChunk;
import com.devknow.vector.VectorStoreService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

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
        // 置信度调节
        if (confidence < 0.5) {
            baseLambda -= 0.10; // 低置信 → 扩多样性，多捞候选给 LLM 判断
        } else if (confidence > 0.8) {
            baseLambda += 0.10; // 高置信 → 聚焦相关，减少噪声
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
                        // 取最短跳数（多个路径时最短的优先）
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

    public float[] embed(Long userId, String text) {
        float[] v = embeddingModel.embed(text).content().vector();
        tokenAuditService.record(userId, "EMBEDDING", text.length() / 2, 0);
        return v;
    }

    public List<ScoredChunk> retrieveCode(Long userId, String question) {
        return retrieveCode(userId, null, question);
    }

    /**
     * 代码检索通道，支持按项目隔离。
     *
     * @param userId    用户 ID
     * @param projectId 项目 ID（null 时默认全量搜索）
     * @param question  查询问题
     */
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

    // ==================== 层级感知检索 ====================

    public RagResult levelAwareRetrieve(Long userId, String question) {
        return levelAwareRetrieve(userId, null, question);
    }

    /**
     * 层级感知检索（A+B 混合 + MMR + 同义词扩展）。
     *
     * @param userId    用户 ID
     * @param projectId 项目 ID（代码检索用，可为 null）
     * @param question  用户问题
     */
    public RagResult levelAwareRetrieve(Long userId, Long projectId, String question) {
        UserKnowledgeRole userRole = getUserKnowledgeRole(userId);

        LevelResult levelResult = levelClassifier.classify(question);
        int targetLevel = levelResult.getLevel();
        double llmConfidence = levelResult.getConfidence();

        RoleLevelMapper.AdjustedPlan plan = roleLevelMapper.adjust(userRole, targetLevel, llmConfidence);
        int[] searchLevels = plan.getSearchLevels();
        double confidence = plan.getAdjustedConfidence();
        boolean needRouteB = plan.isNeedRouteB();

        // 步骤 3.5: HyDE — 生成假设文档提升检索召回
        String hydeQuery = hydeGenerator.generateHypothesis(question);

        // 步骤 4: 向量搜索（A 路）— 使用 HyDE 增强后的查询
        float[] queryVector = embed(userId, hydeQuery);
        List<ScoredChunk> vectorHits = new ArrayList<>(vectorStoreService.searchByLevels(queryVector, vectorTopK, searchLevels));
        for (ScoredChunk c : vectorHits) c.setSource("");

        // 步骤 4-B: 角色补刀
        if (needRouteB) {
            List<ScoredChunk> backupHits = vectorStoreService.searchByLevels(queryVector, vectorTopK,
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

        // 步骤 5: 关键词搜索 + 同义词扩展
        List<String> expandedTerms = queryExpander.expand(question);
        String expandedQuery = String.join(" ", expandedTerms);

        List<Integer> levelList = new ArrayList<>();
        for (int l : searchLevels) levelList.add(l);
        List<DocumentChunk> kwRaw = chunkRepository.keywordSearchByLevel(expandedQuery, levelList, keywordTopK);
        List<ScoredChunk> keywordHits = kwRaw.stream()
                .map(c -> new ScoredChunk(c.getId(), c.getDocId(), c.getSeq(), "", c.getContent(), 0.0, "keyword"))
                .toList();

        // 步骤 6: RRF 融合
        List<ScoredChunk> fused = RrfFusion.fuse(vectorHits, keywordHits, rrfK);

        // 步骤 7: 图感知 MMR 多样性去重
        //   — 候选池动态计算（rerankTopN × 4，至少 20）
        //   — λ 根据问题层级 + 置信度自适应调节
        //   — pairSim 考虑知识图谱中文档关联关系，保留互补信息
        int candidatePoolSize = Math.max(rerankTopN * 4, 20);
        List<ScoredChunk> candidates = fused.size() > candidatePoolSize
                ? fused.subList(0, candidatePoolSize)
                : fused;

        Map<String, float[]> vectorMap = vectorStoreService.retrieveVectors(
                candidates.stream().map(ScoredChunk::getChunkId).collect(Collectors.toList()));

        double adaptiveLambda = computeAdaptiveLambda(targetLevel, confidence);
        Map<Long, Set<Long>> graphRelationMap = buildGraphRelationMap(candidates, 2);

        List<ScoredChunk> diverse = mmrSelector.select(
                queryVector, candidates,
                (docId, chunkId) -> vectorMap.get(String.valueOf(chunkId)),
                adaptiveLambda, rerankTopN, graphRelationMap);

        // 步骤 8: 代码结构感知重排序 + Cross-encoder 精排 + 图谱扩展
        List<ScoredChunk> topN = reranker.rerank(question, diverse, rerankTopN, projectId);
        List<ScoredChunk> ceRanked = crossEncoderReranker.rerank(question, topN, rerankTopN);
        List<ScoredChunk> expanded = graphExpander.expand(ceRanked, 3, 2);

        // 步骤 9: CRAG 纠错评估 — 评估检索质量，触发补搜或降级
        confidence = calculateConfidence(vectorHits, keywordHits, fused, confidence);
        CorrectiveEvaluator.EvaluationResult cragResult = correctiveEvaluator.evaluate(
                expanded, question,
                originalResults -> {
                    // 补搜策略：放宽层级范围 + 扩大候选池
                    int[] broaderLevels = new int[]{1, 2, 3, 4, 5};
                    float[] retryVector = embed(userId, question);
                    List<ScoredChunk> retryHits = vectorStoreService.searchByLevels(retryVector, vectorTopK * 2, broaderLevels);
                    if (!retryHits.isEmpty()) {
                        for (ScoredChunk c : retryHits) c.setSource("retry");
                    }
                    return retryHits;
                });

        List<ScoredChunk> cragChunks = cragResult.verdict() == CorrectiveEvaluator.EvaluationVerdict.LOW_CONFIDENCE
                ? List.of()
                : cragResult.chunks();

        // 步骤 10: 幻觉第一关 — LLM 逐条过滤不相关的 chunk
        List<ScoredChunk> finalChunks = !cragChunks.isEmpty()
                ? hallucinationGuard.executeCheckpoint1(question, cragChunks)
                : cragChunks;

        log.info("levelAwareRetrieve: q={}, role={}, level={}, conf={}, A_hits={}, B={}, CRAG={}, MMR(lambda={})={}/{}, pool={}, graphRelated={}, expanded={}, H1_filtered={}",
                question, userRole, targetLevel, String.format("%.2f", confidence),
                vectorHits.size(), needRouteB, cragResult.verdict(), String.format("%.2f", adaptiveLambda),
                diverse.size(), candidates.size(), candidatePoolSize,
                graphRelationMap.size(), expanded.size() - topN.size());

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

        // 分差因子：第 1 名比第 2 名高越多越可信
        double gapFactor = 1.0;
        if (vectorHits.size() >= 2) {
            double gap = vectorHits.get(0).getScore() - vectorHits.get(1).getScore();
            gapFactor = Math.min(1.2, 1.0 + gap * 2);
        }

        // 关键词确认率
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

        // 融合
        double finalConf = topScore * gapFactor * confirmRate;
        // 融合角色调整
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
