package com.devknow.codeindex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.devknow.vector.VectorRecord;
import com.devknow.vector.VectorStoreService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 代码索引服务。
 *
 * <p>功能：
 * <ul>
 *   <li>全量索引 {@link #indexProject} — 遍历所有文件，解析→MySQL→Redis→ripple 缓存</li>
 *   <li>波及重建 {@link #indexIncremental} — Git diff → 定位变更方法 → 找出调用方 → 重索引波及文件</li>
 * </ul>
 *
 * <p>数据流（每个 CodeUnit）：
 * <pre>
 * CodeParser.parse() → CodeUnit
 *   ├── MySQL code_unit 表（结构数据，用于反向调用链查询）
 *   ├── Redis vec:{projectId}:code:{unitId}（向量，用于语义检索）
 *   └── Redis ripple:callers:{projectId}:{methodName}（反向索引，用于波及重建）
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeIndexService {

    private final CodeParser codeParser;
    private final EmbeddingModel embeddingModel;
    private final VectorStoreService vectorStoreService;
    private final CodeUnitEntityRepository codeUnitRepo;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 可索引的文件后缀 */
    private static final List<String> INDEXABLE_EXTS = List.of(
            "java", "kt", "go", "py", "js", "ts", "jsx", "tsx",
            "rs", "c", "cpp", "h", "cs", "rb", "php", "swift",
            "scala", "vue"
    );

    // ======================== 全量索引 ========================

    /**
     * 全量索引一个项目。
     * 写入 MySQL + Redis 向量 + ripple 反向索引。
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

            log.info("全量索引开始: projectId={}, files={}", projectId, sourceFiles.size());

            // 清空旧数据（防重复导入导致半截脏数据）
            clearProjectData(projectId);

            // SCIP 模式：一次性解析整个项目的 index.scip
            Map<String, List<CodeUnit>> scipUnits = codeParser.parseProject(repoPath.toString());

            for (Path file : sourceFiles) {
                if (closed != null && closed.get()) {
                    log.warn("全量索引被中断: projectId={}", projectId);
                    break;
                }
                try {
                    String filePath = repoPath.relativize(file).toString().replace('\\', '/');

                    if (codeParser.getMode() == CodeIndexMode.SCIP) {
                        List<CodeUnit> units = scipUnits.get(filePath);
                        if (units == null || units.isEmpty()) continue;
                        for (CodeUnit unit : units) {
                            unit.setProjectId(projectId);
                            unit.setRepoName(repoName);
                            saveCodeUnit(projectId, unit);
                        }
                        totalMethods += units.size();
                        fileCount++;
                    } else {
                        String source = Files.readString(file, StandardCharsets.UTF_8);
                        if (source.isBlank() || source.length() > 500_000) continue;
                        String ext = getExtension(filePath);
                        List<CodeUnit> units = codeParser.parse(filePath, source, ext);
                        if (units.isEmpty()) continue;
                        for (CodeUnit unit : units) {
                            unit.setProjectId(projectId);
                            unit.setRepoName(repoName);
                            saveCodeUnit(projectId, unit);
                        }
                        totalMethods += units.size();
                        fileCount++;
                    }

                    if (emitter != null && fileCount % 10 == 0) {
                        sendProgress(emitter, closed, String.format(
                                "解析中... (%d/%d 文件, %d 方法)", fileCount, sourceFiles.size(), totalMethods));
                    }
                } catch (IOException e) {
                    log.debug("跳过文件: {}", file, e.getMessage());
                }
            }

            // 保存当前 HEAD commit hash（下次索引时用于 diff）
            saveLastIndexedCommit(projectId, repoPath);

            log.info("全量索引完成: projectId={}, {} 文件, {} 方法", projectId, fileCount, totalMethods);

        } catch (IOException e) {
            log.error("全量索引失败: projectId={}", projectId, e);
        }
        return totalMethods;
    }

    // ======================== 波及重建（增量索引） ========================

    /**
     * 波及重建：只索引变更文件 + 调用变更方法的文件。
     *
     * <p>算法：
     * <ol>
     *   <li>Git diff 找出变更文件列表</li>
     *   <li>从变更文件中提取所有方法名（新旧合并）</li>
     *   <li>查询 MySQL code_unit 表：哪些文件调了这些方法</li>
     *   <li>合并去重：变更文件 + 调用方文件 = 需索引的文件</li>
     *   <li>只重索引这组文件（不走全量）</li>
     * </ol>
     *
     * @param projectId 项目 ID
     * @param repoName  仓库名
     * @param repoPath  仓库本地路径
     * @param changedFiles Git diff 得到的变更文件列表
     * @return 重索引的方法数
     */
    public int indexIncremental(Long projectId, String repoName, Path repoPath,
                                 List<String> changedFiles) {
        if (changedFiles == null || changedFiles.isEmpty()) {
            log.info("波及重建: 无变更文件, projectId={}", projectId);
            return 0;
        }

        log.info("波及重建开始: projectId={}, changedFiles={}", projectId, changedFiles.size());

        // Step 1: 从变更文件中提取所有涉及的方法名
        Set<String> affectedMethods = new HashSet<>();
        for (String cf : changedFiles) {
            // 从 MySQL 获取旧版本的方法名（已索引的）
            List<CodeUnitEntity> oldUnits = codeUnitRepo.findByProjectIdAndFilePath(projectId, cf);
            for (CodeUnitEntity old : oldUnits) {
                affectedMethods.add(old.getMethodName());
            }
            // 从文件系统读取新版本，解析出新方法名
            Path fullPath = repoPath.resolve(cf);
            if (Files.exists(fullPath)) {
                try {
                    String source = Files.readString(fullPath, StandardCharsets.UTF_8);
                    String ext = getExtension(cf);
                    List<CodeUnit> newUnits = codeParser.parse(cf, source, ext);
                    for (CodeUnit nu : newUnits) {
                        affectedMethods.add(nu.getMethodName());
                    }
                } catch (IOException e) {
                    log.debug("波及重建: 无法读取文件 {}", cf);
                }
            }
        }

        if (affectedMethods.isEmpty()) {
            log.info("波及重建: 未检测到变更方法, projectId={}", projectId);
            return 0;
        }

        log.debug("波及重建: affectedMethods={}", affectedMethods);

        // Step 2: 查询 MySQL，找出调用了这些方法的所有文件
        Set<String> filesToReindex = new HashSet<>(changedFiles);  // 变更文件本身
        for (String method : affectedMethods) {
            try {
                List<String> callers = codeUnitRepo.findCallersByMethodName(projectId, method);
                filesToReindex.addAll(callers);
            } catch (Exception e) {
                log.debug("波及重建: 查询调用方失败 method={}", method);
            }
        }

        log.info("波及重建: filesToReindex={} (changed={}, ripple={})",
                filesToReindex.size(), changedFiles.size(), filesToReindex.size() - changedFiles.size());

        // Step 3: 清空这些文件的旧数据，重新索引
        int totalMethods = 0;
        for (String filePath : filesToReindex) {
            Path fullPath = repoPath.resolve(filePath);
            if (!Files.exists(fullPath)) continue;

            try {
                // 删除该文件的旧索引（MySQL + Redis + ripple）
                deleteFileData(projectId, filePath);

                // 重新索引
                String source = Files.readString(fullPath, StandardCharsets.UTF_8);
                if (source.isBlank() || source.length() > 500_000) continue;

                String ext = getExtension(filePath);
                List<CodeUnit> units = codeParser.parse(filePath, source, ext);
                for (CodeUnit unit : units) {
                    unit.setProjectId(projectId);
                    unit.setRepoName(repoName);
                    saveCodeUnit(projectId, unit);
                }
                totalMethods += units.size();

            } catch (IOException e) {
                log.debug("波及重建: 跳过文件 {}", filePath);
            }
        }

        // 更新索引 commit hash
        saveLastIndexedCommit(projectId, repoPath);

        log.info("波及重建完成: projectId={}, reindexedMethods={}", projectId, totalMethods);
        return totalMethods;
    }

    // ======================== 数据写入（MySQL + Redis + ripple） ========================

    /**
     * 保存单个 CodeUnit 到三处存储。
     */
    private void saveCodeUnit(Long projectId, CodeUnit unit) {
        // ① MySQL code_unit 表（结构数据，反向调用链查询）
        CodeUnitEntity entity = toEntity(projectId, unit);
        codeUnitRepo.save(entity);

        // ② Redis 向量存储（语义检索）
        saveVector(projectId, unit);

        // ③ Redis ripple 反向索引（波及重建用）
        buildRippleCache(projectId, unit);
    }

    /**
     * CodeUnit → CodeUnitEntity。
     */
    private CodeUnitEntity toEntity(Long projectId, CodeUnit unit) {
        return CodeUnitEntity.builder()
                .projectId(projectId)
                .filePath(unit.getFilePath())
                .packageName(unit.getPackageName())
                .className(unit.getClassName())
                .methodName(unit.getMethodName())
                .signature(unit.getSignature())
                .comment(unit.getComment())
                .body(unit.getBody())
                .startLine(unit.getStartLine())
                .endLine(unit.getEndLine())
                .calls(toJson(unit.getCalls()))
                .enrichedCalls(toJson(unit.getEnrichedCalls()))
                .resolvedType(unit.getResolvedType())
                .annotations(toJson(unit.getAnnotations()))
                .language(unit.getLanguage())
                .checksum(unit.getChecksum())
                .build();
    }

    /**
     * 写入 Redis 向量。
     */
    private void saveVector(Long projectId, CodeUnit unit) {
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

        vectorStoreService.saveWithKey("vec:" + projectId + ":code:", record);
    }

    /**
     * 构建 ripple 反向索引："方法名 → 哪些文件调用了它"。
     * 存 Redis Set 结构，查询时 SMEMBERS 秒回。
     */
    private void buildRippleCache(Long projectId, CodeUnit unit) {
        // 用 enrichedCalls（优先，SCIP 精度高）或 calls（Tree-sitter 基础）
        List<String> callList = unit.getEnrichedCalls() != null && !unit.getEnrichedCalls().isEmpty()
                ? unit.getEnrichedCalls()
                : unit.getCalls();

        if (callList == null) return;

        for (String call : callList) {
            // 从 "com.trade.service.OrderService.createOrder(CreateReq)" 中提取 "createOrder"
            String methodName = extractSimpleMethodName(call);
            if (methodName != null) {
                String redisKey = "ripple:callers:" + projectId + ":" + methodName;
                redis.opsForSet().add(redisKey, unit.getFilePath());
            }
        }
    }

    /**
     * 从全限定调用串中提取方法名。
     * "com.trade.service.OrderService.createOrder(CreateReq)" → "createOrder"
     * "validate" → "validate"
     */
    private String extractSimpleMethodName(String call) {
        if (call == null || call.isBlank()) return null;
        // 去掉参数部分
        String name = call.contains("(") ? call.substring(0, call.indexOf('(')) : call;
        // 取最后一段（方法名）
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    // ======================== 数据清理 ========================

    /**
     * 清空项目的全部旧索引数据。
     */
    private void clearProjectData(Long projectId) {
        // MySQL
        codeUnitRepo.deleteByProjectId(projectId);
        // Redis 向量（vec:projectId:code:*）
        scanAndDelete("vec:" + projectId + ":code:*");
        // Redis ripple 缓存（ripple:callers:projectId:*）
        scanAndDelete("ripple:callers:" + projectId + ":*");
        log.info("已清空 projectId={} 的旧索引数据", projectId);
    }

    /**
     * 删除单个文件的相关索引数据。
     */
    private void deleteFileData(Long projectId, String filePath) {
        // MySQL
        List<CodeUnitEntity> oldUnits = codeUnitRepo.findByProjectIdAndFilePath(projectId, filePath);
        codeUnitRepo.deleteAll(oldUnits);

        // 清理该文件在 ripple 缓存中的记录（从每个 Set 中移除该文件路径）
        for (CodeUnitEntity old : oldUnits) {
            removeFromRippleCache(projectId, filePath, old.getCalls());
            removeFromRippleCache(projectId, filePath, old.getEnrichedCalls());
        }
        // 不需要清理向量（覆盖写入即可，向量 Key 按 hash 不按文件）
    }

    private void removeFromRippleCache(Long projectId, String filePath, String callsJson) {
        if (callsJson == null || callsJson.isBlank()) return;
        try {
            List<String> calls = objectMapper.readValue(callsJson, List.class);
            for (Object call : calls) {
                String methodName = extractSimpleMethodName(call.toString());
                if (methodName != null) {
                    redis.opsForSet().remove("ripple:callers:" + projectId + ":" + methodName, filePath);
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * 保存最后一次成功索引的 commit hash（用于下次 diff）。
     */
    private void saveLastIndexedCommit(Long projectId, Path repoPath) {
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(repoPath.toFile())) {
            var commits = git.log().setMaxCount(1).call();
            if (commits.iterator().hasNext()) {
                var commit = commits.iterator().next();
                String hash = commit.getName();
                long timestamp = commit.getCommitTime() * 1000L;  // JGit 用秒，转毫秒

                // commit hash（用于正常 diff）
                redis.opsForValue().set("index:commit:" + projectId, hash);
                // 时间戳（用于 force push 后基于时间的 diff 降级）
                redis.opsForValue().set("index:timestamp:" + projectId, String.valueOf(timestamp));

                log.debug("已保存索引点: projectId={}, commit={}, time={}",
                        projectId, hash.substring(0, 8), timestamp);
            }
        } catch (Exception e) {
            log.warn("保存索引点失败: projectId={}", projectId);
        }
    }

    private void scanAndDelete(String pattern) {
        try (var cursor = redis.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(pattern).count(500).build())) {
            while (cursor.hasNext()) {
                redis.delete(cursor.next());
            }
        }
    }

    // ======================== 辅助方法 ========================

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

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return null;
        }
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
