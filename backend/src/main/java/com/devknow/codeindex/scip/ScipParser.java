package com.devknow.codeindex.scip;

import com.devknow.codeindex.CodeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SCIP 索引解析器。
 *
 * <p>读取项目目录下的 {@code index.scip} 文件（由外部 SCIP indexer 生成），
 * 提取符号信息，增强 Tree-sitter 解析的 CodeUnit。
 *
 * <p>功能：
 * <ul>
 *   <li>将方法名级别的调用链提升为类.方法级别的精确调用链</li>
 *   <li>补充 resolvedType（方法所属类的全限定名）</li>
 *   <li>跨文件引用解析</li>
 * </ul>
 *
 * <p>当前为框架实现，实际 SCIP protobuf 解析需引入 {@code com.sourcegraph:scip-java} 依赖后完善。
 * 启用条件：{@code app.codeindex.scip.enabled=true} + 项目根目录存在 {@code index.scip} 文件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScipParser {

    private final ScipConfig config;

    /**
     * 检查 SCIP 是否已启用。
     */
    public boolean isEnabled() {
        return config.isEnabled();
    }

    /**
     * 对 Tree-sitter 解析的 CodeUnit 列表做 SCIP 符号增强。
     *
     * @param units         Tree-sitter 解析的基础 CodeUnit
     * @param filePath      当前源码文件路径
     * @param scipIndexPath SCIP 索引文件路径（通常为项目目录下的 index.scip）
     * @return 增强后的 CodeUnit 列表
     */
    public List<CodeUnit> enhance(List<CodeUnit> units, String filePath, String scipIndexPath) {
        if (!config.isEnabled() || units.isEmpty()) {
            return units;
        }

        // 检查 index.scip 是否存在
        Path indexFile = Path.of(scipIndexPath != null ? scipIndexPath : config.getIndexFileName());
        if (!Files.exists(indexFile)) {
            log.debug("SCIP 索引文件不存在，跳过增强: {}", indexFile);
            return units;
        }

        try {
            // ──────────────────────────────────────────────
            // SCIP protobuf 解析入口
            // ──────────────────────────────────────────────
            // 当前版本为框架占位。完整实现需要：
            //
            // 1. Maven 依赖（取消注释）：
            //    <dependency>
            //        <groupId>com.sourcegraph</groupId>
            //        <artifactId>scip-java</artifactId>
            //        <version>0.7.0</version>
            //    </dependency>
            //
            // 2. 解析 index.scip：
            //    Index index = Index.parseFrom(Files.readAllBytes(indexFile));
            //
            // 3. 构建符号映射表：
            //    Map<String, ScipSymbol> symbols = new HashMap<>();
            //    for (Document doc : index.getDocumentsList()) {
            //        if (!doc.getRelativePath().equals(filePath)) continue;
            //        for (Occurrence occ : doc.getOccurrencesList()) {
            //            String symbol = occ.getSymbol();
            //            // 匹配到 CodeUnit 的方法，设置 enrichedCalls / resolvedType
            //        }
            //    }
            //
            // ──────────────────────────────────────────────

            log.info("SCIP 索引文件已找到，增强处理: {} ({} 个方法)", filePath, units.size());
            return doEnhance(units, indexFile);

        } catch (Exception e) {
            log.warn("SCIP 解析失败: {}", e.getMessage());
            return units;
        }
    }

    /**
     * 占位实现：按文件名匹配规则模拟增强。
     * 实际实现应解析 SCIP protobuf 获取精确符号信息。
     */
    private List<CodeUnit> doEnhance(List<CodeUnit> units, Path indexFile) {
        // 占位：框架层面不做实际增强，直接返回原始 units
        // 实际实现时在此处消费 SCIP protobuf 的 Document / Occurrence / SymbolInformation
        return units;
    }

    /**
     * 获取 SCIP 索引文件所在目录。
     * 从项目根目录递归向上查找 index.scip。
     */
    public Optional<Path> findIndexFile(String projectDir) {
        if (!config.isEnabled()) return Optional.empty();

        Path dir = Path.of(projectDir);
        Path indexFile = dir.resolve(config.getIndexFileName());
        if (Files.exists(indexFile)) {
            return Optional.of(indexFile);
        }
        return Optional.empty();
    }
}
