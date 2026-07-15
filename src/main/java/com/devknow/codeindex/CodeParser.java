package com.devknow.codeindex;

import com.devknow.codeindex.tree.LanguageMapping;
import com.devknow.codeindex.tree.TreeSitterParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 代码解析器入口。
 *
 * <p>分层架构（面试点）：
 * <ol>
 *   <li>Tree-sitter 基础解析（语法级，所有语言统一）
 *       → 提取：方法名 / 签名 / 注释 / 行号 / 方法体 / 调用的方法名</li>
 *   <li>LanguageEnhancer 精度补偿（可选，按语言注册）
 *       → Java：JavaParser 类型解析 → 精确到类.方法级别的调用链</li>
 *   <li>无插件的语言天然降级到 Tree-sitter 语法级结果</li>
 * </ol>
 *
 * <p>使用示例：
 * <pre>{@code
 * List<CodeUnit> units = codeParser.parse("OrderService.java", sourceCode, "java");
 * // Java 文件 → units 中的 enrichedCalls 为类.方法级别（JavaEnhancer 补偿）
 *
 * List<CodeUnit> units2 = codeParser.parse("main.go", sourceCode, "go");
 * // Go 文件（无 enhancer）→ units2 中的 calls 为方法名级别
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodeParser {

    private final TreeSitterParser treeSitterParser;
    private final LanguageEnhancerRegistry enhancerRegistry;

    /**
     * 解析单个源码文件，返回方法粒度的 CodeUnit 列表。
     *
     * @param filePath 源码文件路径
     * @param source   源码文本
     * @param language 语言标识（如 "java"、"go"），为 null 时尝试从文件后缀检测
     * @return CodeUnit 列表
     */
    public List<CodeUnit> parse(String filePath, String source, String language) {
        if (language == null || language.isBlank()) {
            language = LanguageMapping.detectLanguage(filePath);
        }
        if (language == null) {
            log.debug("无法检测语言: {}", filePath);
            return List.of();
        }

        // ========== 第 1 层：Tree-sitter 基础解析 ==========
        List<CodeUnit> basicUnits = treeSitterParser.parse(filePath, source, language);
        if (basicUnits.isEmpty()) {
            return List.of();
        }

        // ========== 第 2 层：LanguageEnhancer 精度补偿 ==========
        LanguageEnhancer enhancer = enhancerRegistry.get(language);
        if (enhancer != null) {
            try {
                List<CodeUnit> enhanced = enhancer.enhance(filePath, basicUnits);
                log.debug("CodeParser: {} ({}): {} methods, enhanced by {}", filePath, language,
                        enhanced.size(), enhancer.getClass().getSimpleName());
                return enhanced;
            } catch (Exception e) {
                log.warn("LanguageEnhancer 执行失败: {} ({}), 降级到 Tree-sitter 基础结果",
                        filePath, language, e);
                return basicUnits;
            }
        }

        // 无 enhancer → 直接返回 Tree-sitter 基础结果
        log.debug("CodeParser: {} ({}): {} methods, 无增强插件", filePath, language, basicUnits.size());
        return basicUnits;
    }
}
