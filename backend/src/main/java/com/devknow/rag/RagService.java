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
import com.devknow.vector.ScoredChunk;
import com.devknow.vector.VectorStoreService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final EmbeddingModel embeddingModel;
    private final VectorStoreService vectorStoreService;
    private final DocumentChunkRepository chunkRepository;
    private final Reranker reranker;
    private final MmrSelector mmrSelector;
    private final QueryExpander queryExpander;
    private final TokenAuditService tokenAuditService;
    private final GraphExpander graphExpander;
    private final LevelClassifier levelClassifier;
    private final RoleLevelMapper roleLevelMapper;
    private final UserRepository userRepository;

    @Value("${app.rag.vector-top-k}")  private int vectorTopK;
    @Value("${app.rag.keyword-top-k}") private int keywordTopK;
    @Value("${app.rag.rerank-top-n}")  private int rerankTopN;
    @Value("${app.rag.rrf-k}")         private int rrfK;

    private static final double MMR_LAMBDA = 0.7;
    private static final int MMR_CANDIDATE_POOL = 12;

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

    public String buildCodeContext(List<ScoredChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            ScoredChunk c = chunks.get(i);
            sb.append("[片段").append(i + 1)
              .append(" 文件:").append(c.getFileName() != null ? c.getFileName() : "未知")
              .append(" 行:").append(c.getSeq())
              .append("]\n")
              .append(c.getContent()).append("\n\n");
        }
        return sb.toString();
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

        // 步骤 4: 向量搜索（A 路）
        float[] queryVector = embed(userId, question);
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

        // 步骤 7: MMR 多样性去重
        List<ScoredChunk> candidates = fused.size() > MMR_CANDIDATE_POOL
                ? fused.subList(0, MMR_CANDIDATE_POOL)
                : fused;

        Map<String, float[]> vectorMap = vectorStoreService.retrieveVectors(
                candidates.stream().map(ScoredChunk::getChunkId).collect(Collectors.toList()));

        List<ScoredChunk> diverse = mmrSelector.select(
                queryVector, candidates,
                (docId, chunkId) -> vectorMap.get(chunkId + ":" + chunkId),
                MMR_LAMBDA, rerankTopN);

        // 步骤 8: 重排序 + 图谱扩展
        List<ScoredChunk> topN = reranker.rerank(question, diverse, rerankTopN);
        List<ScoredChunk> expanded = graphExpander.expand(topN, 3, 2);

        // 置信度计算：多因子加权
        confidence = calculateConfidence(vectorHits, keywordHits, fused, confidence);

        log.info("levelAwareRetrieve: q={}, role={}, level={}, conf={}, A_hits={}, B={}, MMR={}/{}, expanded={}",
                question, userRole, targetLevel, String.format("%.2f", confidence),
                vectorHits.size(), needRouteB, diverse.size(), candidates.size(),
                expanded.size() - topN.size());

        return new RagResult(expanded, confidence);
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

    private int[] expandRange(int center, int offset) {
        int low = Math.max(1, center - offset);
        int high = Math.min(5, center + offset);
        int[] range = new int[high - low + 1];
        for (int i = 0; i < range.length; i++) range[i] = low + i;
        return range;
    }

    public List<ScoredChunk> testGraphExpand(int topK, int maxExtra, int hops) {
        List<DocumentChunk> docChunks = chunkRepository.findAll();
        if (docChunks.isEmpty()) return List.of();
        List<ScoredChunk> hits = docChunks.stream().limit(topK)
                .map(c -> new ScoredChunk(c.getId(), c.getDocId(), c.getSeq(), "", c.getContent(), 1.0, ""))
                .toList();
        return graphExpander.expand(new ArrayList<>(hits), maxExtra, hops);
    }
}
