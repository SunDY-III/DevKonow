package com.devknow.codeindex;

import com.devknow.codeindex.scip.ScipCodeParser;
import com.devknow.codeindex.tree.LanguageMapping;
import com.devknow.codeindex.tree.TreeSitterParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码解析器入口（运行时三模式切换）。
 *
 * <p>模式通过 {@link CodeIndexModeService} 动态切换，无需重启。
 * <ul>
 *   <li><b>tree-sitter</b>（默认）：轻量级，零外部依赖</li>
 *   <li><b>scip</b>：性能级，需要 index.scip</li>
 *   <li><b>hybrid</b>：SCIP 优先，不可用时自动降级 Tree-sitter</li>
 * </ul>
 */
@Slf4j
@Component
public class CodeParser {

    private final TreeSitterParser treeSitterParser;
    private final ScipCodeParser scipParser;
    private final CodeIndexModeService modeService;

    public CodeParser(TreeSitterParser treeSitterParser, ScipCodeParser scipParser,
                      CodeIndexModeService modeService) {
        this.treeSitterParser = treeSitterParser;
        this.scipParser = scipParser;
        this.modeService = modeService;
        log.info("CodeParser 初始化: mode={}（运行时动态切换）", modeService.getCurrentMode());
    }

    public CodeIndexMode getMode() {
        return modeService.getCurrentMode();
    }

    /**
     * 解析单个源码文件。
     * <ul>
     *   <li>TREE_SITTER 模式：走 Tree-sitter 解析</li>
     *   <li>SCIP/HYBRID 模式：走 SCIP 解析（HYBRID 不可用时降级 Tree-sitter）</li>
     * </ul>
     */
    public List<CodeUnit> parse(String filePath, String source, String language) {
        CodeIndexMode mode = modeService.getCurrentMode();

        if (mode == CodeIndexMode.SCIP) {
            return List.of(); // SCIP 模式用 parseProject
        }

        if (mode == CodeIndexMode.HYBRID) {
            // HYBRID 模式：先尝试用 SCIP 项目索引解析，不可用时降级 Tree-sitter
            String projectDir = modeService.getCurrentProjectDir();
            if (projectDir != null) {
                List<CodeUnit> scipUnits = scipParser.parseFile(projectDir, filePath);
                if (scipUnits != null && !scipUnits.isEmpty()) {
                    log.debug("CodeParser [hybrid/scip]: {}: {} methods", filePath, scipUnits.size());
                    return scipUnits;
                }
            }
            // 降级到 Tree-sitter
            log.debug("CodeParser [hybrid→tree-sitter]: {} (SCIP 不可用)", filePath);
        }

        // TREE_SITTER 模式 或 HYBRID 降级路径
        if (language == null || language.isBlank()) {
            language = LanguageMapping.detectLanguage(filePath);
        }
        if (language == null) {
            log.debug("无法检测语言: {}", filePath);
            return List.of();
        }

        List<CodeUnit> units = treeSitterParser.parse(filePath, source, language);
        if (!units.isEmpty()) {
            log.debug("CodeParser [tree-sitter]: {} ({}): {} methods", filePath, language, units.size());
        }
        return units;
    }

    /**
     * 从 index.scip 解析整个项目。
     * <ul>
     *   <li>SCIP 模式：解析 index.scip 返回全部结果</li>
     *   <li>HYBRID 模式：解析 index.scip 用于增量 parse() 降级</li>
     *   <li>TREE_SITTER 模式：返回空映射（请使用 {@link #parse(String, String, String)}）</li>
     * </ul>
     */
    public Map<String, List<CodeUnit>> parseProject(String projectDir) {
        if (modeService.getCurrentMode() == CodeIndexMode.TREE_SITTER) {
            return Map.of();
        }

        // 记录项目目录（供 HYBRID 模式的 parse() 降级使用）
        modeService.setCurrentProjectDir(projectDir);

        // 尝试加载 SCIP 索引
        List<CodeUnit> allUnits = scipParser.parseProject(projectDir);

        String modeLabel = modeService.getCurrentMode() == CodeIndexMode.HYBRID ? "hybrid" : "scip";

        if (allUnits.isEmpty() && modeService.getCurrentMode() == CodeIndexMode.HYBRID) {
            log.info("CodeParser [hybrid]: SCIP 索引为空或无 index.scip，将逐文件使用 Tree-sitter");
            return Map.of();
        }

        Map<String, List<CodeUnit>> byFile = new HashMap<>();
        for (CodeUnit unit : allUnits) {
            byFile.computeIfAbsent(unit.getFilePath(), k -> new java.util.ArrayList<>()).add(unit);
        }

        log.info("CodeParser [{}]: {} 个文件, {} 个方法", modeLabel, byFile.size(), allUnits.size());
        return byFile;
    }
}
