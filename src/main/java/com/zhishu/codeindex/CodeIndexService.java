package com.zhishu.codeindex;

import com.zhishu.vector.VectorRecord;
import com.zhishu.vector.VectorStoreService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 代码索引服务。
 *
 * <p>遍历 Git 仓库中的源码文件，逐文件解析为 {@link CodeUnit}，
 * 将每个方法存入 MySQL + Redis 向量存储，供后续语义检索。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeIndexService {

    private final CodeParser codeParser;
    private final EmbeddingModel embeddingModel;
    private final VectorStoreService vectorStoreService;

    /** 可索引的文件后缀（按语言分类） */
    private static final List<String> INDEXABLE_EXTS = List.of(
            "java", "kt", "go", "py", "js", "ts", "jsx", "tsx",
            "rs", "c", "cpp", "h", "cs", "rb", "php", "swift",
            "scala", "vue"
    );

    /**
     * 全量索引一个项目。
     *
     * @param projectId  项目 ID
     * @param repoName   仓库名
     * @param repoPath   仓库本地路径
     * @param emitter    SSE 推送（可为 null）
     * @param closed     连接状态
     * @return 索引的方法总数
     */
    public int indexProject(Long projectId, String repoName, Path repoPath,
                             SseEmitter emitter, AtomicBoolean closed) {
        int totalMethods = 0;
        int fileCount = 0;

        try {
            List<Path> sourceFiles;
            try (var stream = Files.walk(repoPath)) {
                sourceFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(f -> isIndexable(f.getFileName().toString()))
                        .toList();
            }

            log.info("开始索引项目 {}: 发现 {} 个可索引文件", projectId, sourceFiles.size());

            for (Path file : sourceFiles) {
                if (closed != null && closed.get()) {
                    log.warn("索引被中断: projectId={}", projectId);
                    break;
                }

                try {
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    if (source.isBlank() || source.length() > 500_000) {
                        continue;  // 跳过空文件或超大文件
                    }

                    String filePath = repoPath.relativize(file).toString().replace('\\', '/');
                    String ext = getExtension(filePath);
                    String language = ext;  // LanguageMapping 内部支持的语言会精确匹配

                    List<CodeUnit> units = codeParser.parse(filePath, source, language);
                    if (units.isEmpty()) {
                        continue;
                    }

                    // 写入每个 CodeUnit
                    for (CodeUnit unit : units) {
                        unit.setProjectId(projectId);
                        unit.setRepoName(repoName);
                        // 写入 Redis 向量
                        saveVector(projectId, unit, source);
                    }

                    totalMethods += units.size();
                    fileCount++;

                    // 每 10 个文件推送一次进度
                    if (emitter != null && fileCount % 10 == 0) {
                        sendProgress(emitter, closed,
                                String.format("解析中... (%d/%d 文件, %d 方法)",
                                        fileCount, sourceFiles.size(), totalMethods));
                    }

                } catch (IOException e) {
                    log.debug("跳过文件: {} ({})", file, e.getMessage());
                }
            }

            log.info("索引完成: projectId={}, {} 文件, {} 方法", projectId, fileCount, totalMethods);

        } catch (IOException e) {
            log.error("索引失败: projectId={}", projectId, e);
        }

        return totalMethods;
    }

    private void saveVector(Long projectId, CodeUnit unit, String source) {
        // 构建向量存储的文本
        String vectorText = buildVectorText(unit);
        float[] vector = embeddingModel.embed(vectorText).content().vector();

        VectorRecord record = VectorRecord.builder()
                .docId(projectId)
                .chunkId((long) unit.hashCode())
                .seq(unit.getStartLine())
                .fileName(unit.getFilePath() + ":" + unit.getMethodName())
                .content(vectorText)
                .vector(vector)
                .build();

        // 使用三段式 Key: vec:{projectId}:code:{unitId}
        // Phase 2.5 接入完整 projectId
        vectorStoreService.saveWithKey("vec:" + projectId + ":code:", record);
    }

    /**
     * 构建向量化的文本表示（用于语义检索）。
     */
    private String buildVectorText(CodeUnit unit) {
        StringBuilder sb = new StringBuilder();
        if (unit.getComment() != null && !unit.getComment().isBlank()) {
            sb.append(unit.getComment()).append('\n');
        }
        sb.append(unit.getSignature() != null ? unit.getSignature() : unit.getMethodName()).append('\n');
        if (unit.getEnrichedCalls() != null && !unit.getEnrichedCalls().isEmpty()) {
            sb.append("calls: ").append(String.join(", ", unit.getEnrichedCalls())).append('\n');
        } else if (unit.getCalls() != null && !unit.getCalls().isEmpty()) {
            sb.append("calls: ").append(String.join(", ", unit.getCalls())).append('\n');
        }
        return sb.toString();
    }

    private boolean isIndexable(String fileName) {
        String ext = getExtension(fileName);
        return INDEXABLE_EXTS.contains(ext);
    }

    private String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    private void sendProgress(SseEmitter emitter, AtomicBoolean closed, String message) {
        if (emitter == null || closed == null || closed.get()) return;
        try {
            emitter.send(SseEmitter.event().name("progress")
                    .data("{\"message\":\"" + message.replace("\"", "'") + "\"}"));
        } catch (IOException e) {
            closed.set(true);
        }
    }
}
