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
 * 代码解析器入口（运行时双模式切换）。
 *
 * <p>模式通过 {@link CodeIndexModeService} 动态切换，无需重启。
 * <ul>
 *   <li><b>tree-sitter</b>（默认）：轻量级，零外部依赖</li>
 *   <li><b>scip</b>：性能级，需要 index.scip</li>
 * </ul>
 * 两种模式互斥，同时只运行一个。
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
     * Tree-sitter 模式：解析单个源码文件。
     * SCIP 模式下返回空列表（请使用 {@link #parseProject(String)}）。
     */
    public List<CodeUnit> parse(String filePath, String source, String language) {
        if (modeService.getCurrentMode() == CodeIndexMode.SCIP) {
            return List.of();
        }

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
     * SCIP 模式：从 index.scip 解析整个项目。
     * Tree-sitter 模式下返回空映射。
     */
    public Map<String, List<CodeUnit>> parseProject(String projectDir) {
        if (modeService.getCurrentMode() == CodeIndexMode.TREE_SITTER) {
            return Map.of();
        }

        // 记录项目目录（用于 SCIP 索引生成）
        modeService.setCurrentProjectDir(projectDir);

        log.info("CodeParser [scip]: 解析项目索引: {}", projectDir);
        List<CodeUnit> allUnits = scipParser.parseProject(projectDir);

        Map<String, List<CodeUnit>> byFile = new HashMap<>();
        for (CodeUnit unit : allUnits) {
            byFile.computeIfAbsent(unit.getFilePath(), k -> new java.util.ArrayList<>()).add(unit);
        }

        log.info("CodeParser [scip]: {} 个文件, {} 个方法", byFile.size(), allUnits.size());
        return byFile;
    }
}
