package com.devknow.rag;

import com.devknow.governance.TokenAuditService;
import com.devknow.knowledge.DocumentChunkRepository;
import com.devknow.vector.ScoredChunk;
import com.devknow.vector.VectorStoreService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 读链路：混合检索（向量 + 关键词）-> RRF 融合 -> 重排序 TopN。
 *
 * <p>v2 新增：代码检索通道 {@link #retrieveCode(Long, String)}，用于按方法粒度搜索代码索引。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final EmbeddingModel embeddingModel;
    private final VectorStoreService vectorStoreService;
    private final DocumentChunkRepository chunkRepository;
    private final Reranker reranker;
    private final TokenAuditService tokenAuditService;

    @Value("${app.rag.vector-top-k}")  private int vectorTopK;
    @Value("${app.rag.keyword-top-k}") private int keywordTopK;
    @Value("${app.rag.rerank-top-n}")  private int rerankTopN;
    @Value("${app.rag.rrf-k}")         private int rrfK;

    public float[] embed(Long userId, String text) {
        float[] v = embeddingModel.embed(text).content().vector();
        tokenAuditService.record(userId, "EMBEDDING", text.length() / 2, 0);
        return v;
    }

    public RagResult retrieve(Long userId, String question) {
        // 通道 1：向量相似度召回（语义近似强，专名/编号弱）
        float[] queryVector = embed(userId, question);
        List<ScoredChunk> vectorHits = vectorStoreService.search(queryVector, vectorTopK);

        // 通道 2：关键词全文召回（专名/编号强，同义改写弱）
        List<ScoredChunk> keywordHits = chunkRepository.keywordSearch(question, keywordTopK).stream()
                .map(c -> new ScoredChunk(c.getId(), c.getDocId(), c.getSeq(), "", c.getContent(), 0.0))
                .toList();

        // RRF 融合（只看排名，规避两路分数量纲不一致）+ 规则重排取 TopN
        List<ScoredChunk> fused = RrfFusion.fuse(vectorHits, keywordHits, rrfK);
        List<ScoredChunk> topN = reranker.rerank(question, fused, rerankTopN);

        // 置信度：优先用向量余弦分（量纲 0~1 稳定），向量为空但关键词有命中时给保底值，
        // 避免冷启动或查询偏专名时 false-negative 路由到工单
        double confidence;
        if (!vectorHits.isEmpty()) {
            confidence = vectorHits.get(0).getScore();
        } else if (!keywordHits.isEmpty()) {
            confidence = 0.5;
        } else {
            confidence = 0;
        }

        log.info("rag retrieve: q={}, vec={}, kw={}, fused={}, confidence={}",
                question, vectorHits.size(), keywordHits.size(), fused.size(), String.format("%.3f", confidence));
        return new RagResult(topN, confidence);
    }

    /**
     * 代码检索通道：按方法粒度搜索代码向量索引。
     * 使用前缀 "vec:0:code:"（Phase 1 默认 projectId=0），
     * Phase 2 引入多项目后切换为 "vec:{projectId}:code:"。
     *
     * @param userId   用户 ID
     * @param question 查询问题
     * @return 检索到的代码块列表（ScoredChunk），按相似度降序
     */
    public List<ScoredChunk> retrieveCode(Long userId, String question) {
        float[] queryVector = embed(userId, question);
        // Phase 1 暂用 projectId=0，Phase 2.5 接入 projectId
        String codePrefix = "vec:0:code:*";
        List<ScoredChunk> results = vectorStoreService.searchByPrefix(codePrefix, queryVector, vectorTopK);
        log.info("rag retrieveCode: q={}, hits={}", question, results.size());
        return results;
    }

    /**
     * 构建代码上下文（用于 LLM Prompt）。
     * 格式：
     * [文件: OrderService.java:42]
     * public OrderVO createOrder(CreateReq req) { ... }
     */
    public String buildCodeContext(List<ScoredChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            ScoredChunk c = chunks.get(i);
            sb.append("[片段").append(i + 1)
              .append(" 文件:").append(c.getFileName() != null ? c.getFileName() : "未知")
              .append(" 行:").append(c.getSeq())  // seq 字段复用为行号
              .append("]\n")
              .append(c.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    /** 组装进 Prompt 的上下文段，带编号供 LLM 引用 */
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
}
