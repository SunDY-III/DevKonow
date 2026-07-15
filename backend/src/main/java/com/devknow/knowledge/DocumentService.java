package com.devknow.knowledge;

import com.devknow.cache.SemanticCacheService;
import com.devknow.common.BizException;
import com.devknow.config.RabbitConfig;
import com.devknow.vector.VectorStoreService;
import io.minio.PutObjectArgs;
import io.minio.MinioClient;
import org.springframework.dao.DataIntegrityViolationException;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    public static final String PROGRESS_KEY = "doc:progress:";   // doc:progress:{docId} -> 0~100

    private final KnowledgeDocumentRepository docRepository;
    private final DocumentChunkRepository chunkRepository;
    private final VectorStoreService vectorStoreService;
    private final SemanticCacheService semanticCacheService;
    private final MinioClient minioClient;
    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redis;

    @Value("${minio.bucket}")
    private String bucket;

    /**
     * 上传入口：只做轻量操作（MD5 去重 + 落 MinIO + 发 MQ），耗时解析全部异步。
     * 幂等设计：同 MD5 文档秒级返回已有记录 —— 与“秒传”同一方法论。
     */
    @SneakyThrows
    public KnowledgeDocument upload(Long userId, MultipartFile file) {
        byte[] bytes = file.getBytes();
        String md5 = DigestUtils.md5DigestAsHex(bytes);

        var existed = docRepository.findByFileMd5AndDeleted(md5, 0);
        if (existed.isPresent()) {
            log.info("doc md5 hit, instant return: {}", md5);
            return existed.get();    // 秒传：不重复解析、不重复向量化
        }

        String objectKey = UUID.randomUUID() + "/" + file.getOriginalFilename();
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket).object(objectKey)
                .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                .contentType(file.getContentType())
                .build());

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setUserId(userId);
        doc.setFileName(file.getOriginalFilename());
        doc.setFileMd5(md5);
        doc.setObjectKey(objectKey);
        doc.setStatus("PARSING");
        doc.setVersion(1);
        doc.setDeleted(0);
        doc.setChunkCount(0);
        try {
            docRepository.save(doc);
        } catch (DataIntegrityViolationException e) {
            // 并发上传同一 MD5：唯一约束 (file_md5, deleted) 阻止重复插入，
            // 返回已有记录，MinIO 残留对象由定期清理任务兜底
            log.info("doc md5 concurrent duplicate, fallback to existing: {}", md5);
            var existing = docRepository.findByFileMd5AndDeleted(md5, 0)
                    .orElseThrow(() -> new BizException("文档上传失败，请稍后重试"));
            return existing;
        }

        redis.opsForValue().set(PROGRESS_KEY + doc.getId(), "0", Duration.ofHours(1));
        rabbitTemplate.convertAndSend(RabbitConfig.DOC_EXCHANGE, RabbitConfig.DOC_ROUTING_KEY, String.valueOf(doc.getId()));
        return doc;
    }

    public List<KnowledgeDocument> listMine(Long userId) {
        return docRepository.findByUserIdAndDeletedOrderByIdDesc(userId, 0);
    }

    public Map<String, Object> progress(Long userId, Long docId) {
        KnowledgeDocument doc = docRepository.findById(docId).orElseThrow(() -> new BizException("文档不存在"));
        // 归属校验（修复越权）：docId 可枚举，防止查看他人文档解析状态
        if (!doc.getUserId().equals(userId)) throw new BizException(403, "无权查看该文档");
        String p = redis.opsForValue().get(PROGRESS_KEY + docId);
        return Map.of("status", doc.getStatus(), "progress", p == null ? "100" : p);
    }

    /**
     * 软删除 + 向量清理 + 语义缓存联动失效。
     * 顺序很重要：先标记软删除（检索侧立即不可见），再清向量与缓存，
     * 即使后两步失败，软删除标记 + version 校验也能挡住旧内容被检索到。
     */
    @Transactional
    public void delete(Long userId, Long docId) {
        KnowledgeDocument doc = docRepository.findById(docId).orElseThrow(() -> new BizException("文档不存在"));
        if (!doc.getUserId().equals(userId)) throw new BizException(403, "无权操作该文档");
        doc.setDeleted(1);
        docRepository.save(doc);

        chunkRepository.deleteByDocId(docId);
        vectorStoreService.deleteByDoc(docId);
        semanticCacheService.invalidateByDoc(docId);   // 知识库变更 -> 相关语义缓存失效
    }
}
