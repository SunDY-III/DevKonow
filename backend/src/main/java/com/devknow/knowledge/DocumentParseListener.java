package com.devknow.knowledge;

import com.rabbitmq.client.Channel;
import com.devknow.config.RabbitConfig;
import com.devknow.governance.TokenAuditService;
import com.devknow.knowledge.graph.KnowledgeGraphService;
import com.devknow.vector.VectorRecord;
import com.devknow.vector.VectorStoreService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * 文档解析消费者：MinIO 取原文 -> Tika 抽取文本 -> 语义切分 -> 逐块向量化 -> 入向量库 + 落 chunk 表。
 * 进度写 Redis，前端轮询 /api/doc/{id}/progress 展示。
 * 手动 ack：成功 ack；失败 nack 不重回队列（标记 FAILED 由用户重传，避免坏文件无限重试）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentParseListener {

    private final KnowledgeDocumentRepository docRepository;
    private final DocumentChunkRepository chunkRepository;
    private final VectorStoreService vectorStoreService;
    private final EmbeddingModel embeddingModel;
    private final TextSplitter textSplitter;
    private final SemanticStructureParser structureParser;
    private final ContextualDescriptionGenerator descriptionGenerator;
    private final MinioClient minioClient;
    private final StringRedisTemplate redis;
    private final TokenAuditService tokenAuditService;
    private final KnowledgeGraphService graphService;
    private final Tika tika = new Tika();

    @Value("${minio.bucket}")
    private String bucket;

    @RabbitListener(queues = RabbitConfig.DOC_PARSE_QUEUE)
    public void onMessage(String docIdStr, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws Exception {
        Long docId = Long.valueOf(docIdStr);
        KnowledgeDocument doc = docRepository.findById(docId).orElse(null);
        if (doc == null) {
            channel.basicAck(tag, false);
            return;
        }
        try {
            parse(doc);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("doc parse failed, docId={}", docId, e);
            doc.setStatus("FAILED");
            docRepository.save(doc);
            // 清理已落库的孤儿 chunk 和向量（parse 中途失败时前序 chunk 已持久化）
            try {
                chunkRepository.deleteByDocId(docId);
                vectorStoreService.deleteByDoc(docId);
            } catch (Exception cleanupErr) {
                log.warn("cleanup partial chunks failed for docId={}", docId, cleanupErr);
            }
            channel.basicNack(tag, false, false);   // 不重回队列
        }
    }

    private void parse(KnowledgeDocument doc) throws Exception {
        // 1. 取原文 + Tika 抽取纯文本（统一支持 PDF/Word/Markdown/TXT）
        String text;
        try (InputStream in = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(doc.getObjectKey()).build())) {
            text = tika.parseToString(in);
        }
        setProgress(doc.getId(), 20);

        // 2a. 语义结构解析（按标题/段落/代码块边界切分）
        List<SemanticChunk> semanticChunks = structureParser.parse(text, doc.getFileName());
        if (semanticChunks.isEmpty()) throw new IllegalStateException("文档无有效文本");

        // 2b. Contextual Retrieval：为每个 Chunk 生成上下文描述
        semanticChunks = descriptionGenerator.enrich(semanticChunks, doc.getFileName());
        setProgress(doc.getId(), 35);

        // 3. 批量向量化入库
        //    嵌入时使用 "上下文描述 + 内容" 拼接，提升检索精度（Contextual Retrieval）
        List<DocumentChunk> chunkEntities = new java.util.ArrayList<>(semanticChunks.size());
        List<VectorRecord> vectorRecords = new java.util.ArrayList<>(semanticChunks.size());
        for (SemanticChunk sc : semanticChunks) {
            // 构建嵌入文本：description + content
            String embeddingText = buildEmbeddingText(sc);
            float[] vector = embeddingModel.embed(embeddingText).content().vector();
            tokenAuditService.record(doc.getUserId(), "EMBEDDING", embeddingText.length() / 2, 0);

            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocId(doc.getId());
            chunk.setDocVersion(doc.getVersion());
            chunk.setSeq(sc.getSeq());
            chunk.setContent(sc.getContent());
            chunk.setContextDescription(sc.getContextDescription());
            chunkEntities.add(chunk);

            // chunkId 暂用占位，saveAll 后从 entity 获取
            vectorRecords.add(new VectorRecord(
                    doc.getId(), doc.getVersion(), null, sc.getSeq(),
                    doc.getFileName(), sc.getContent(), vector,
                    doc.getLevel(), sc.getContextDescription()));
        }
        setProgress(doc.getId(), 50);

        // 4. 批量写入 MySQL
        chunkRepository.saveAll(chunkEntities);

        // 5. 批量写入 Qdrant（回填 chunkId）
        for (int i = 0; i < vectorRecords.size(); i++) {
            vectorRecords.get(i).setChunkId(chunkEntities.get(i).getId());
        }
        vectorStoreService.saveBatch(vectorRecords);

        setProgress(doc.getId(), 95);

        doc.setStatus("READY");
        doc.setChunkCount(semanticChunks.size());
        docRepository.save(doc);

        // 6. 同步到 Neo4j 知识图谱
        try {
            int level = doc.getLevel() != null ? doc.getLevel() : 0;
            graphService.createOrUpdateNode(doc.getId(), doc.getFileName(), level, "");
            String contentPreview = semanticChunks.isEmpty() ? "" : semanticChunks.get(0).getContent();
            graphService.autoBuildRelations(doc.getId(), doc.getFileName(), contentPreview);
        } catch (Exception e) {
            log.warn("知识图谱同步失败（docId={}），不影响主流程: {}", doc.getId(), e.getMessage());
        }

        setProgress(doc.getId(), 100);
        log.info("doc parsed: id={}, chunks={}, contextual={}, neo4j=synced",
                doc.getId(), semanticChunks.size(),
                semanticChunks.stream().filter(c -> c.getContextDescription() != null).count());
    }

    /**
     * 构建用于嵌入的文本：如有上下文描述则拼接，否则只用原文。
     * Contextual Retrieval 核心：描述提供 chunk 在文档中的角色信息，
     * 使向量检索能感知语义上下文而不仅是字面匹配。
     */
    private String buildEmbeddingText(SemanticChunk sc) {
        if (sc.getContextDescription() != null && !sc.getContextDescription().isBlank()) {
            return sc.getContextDescription() + "\n\n" + sc.getContent();
        }
        return sc.getContent();
    }

    private void setProgress(Long docId, int progress) {
        redis.opsForValue().set(DocumentService.PROGRESS_KEY + docId, String.valueOf(progress), Duration.ofHours(1));
    }
}
