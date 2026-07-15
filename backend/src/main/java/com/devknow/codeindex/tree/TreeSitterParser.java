package com.devknow.codeindex.tree;

import com.devknow.codeindex.CodeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tree-sitter 统一语法解析器。
 *
 * <p>所有语言走同一套遍历逻辑，差异只在于 {@link LanguageMapping} 中的节点类型映射表。
 * 输出 {@link CodeUnit} 列表（粒度 = 一个方法/函数），供下游 {@link com.zhishu.codeindex.CodeParser} 使用。
 *
 * <p>解析器实例按语言缓存，避免每次重复创建（TSParser 创建成本较高）。
 */
@Slf4j
@Component
public class TreeSitterParser {

    /** 按语言缓存 TSParser 实例 */
    private final Map<String, TSParser> parserCache = new ConcurrentHashMap<>();

    /**
     * 解析单个源码文件，返回方法粒度的 CodeUnit 列表。
     *
     * @param filePath 源码文件路径（仅用于填充 CodeUnit）
     * @param source   源码文本
     * @param language 语言标识（如 "java"、"go"）
     * @return CodeUnit 列表；如果语言不支持或解析失败则返回空列表
     */
    public List<CodeUnit> parse(String filePath, String source, String language) {
        if (language == null || source == null || source.isBlank()) {
            return List.of();
        }

        TSParser parser = getOrCreateParser(language);
        if (parser == null) {
            log.debug("不支持的 Tree-sitter 语言: {}, file: {}", language, filePath);
            return List.of();
        }

        try {
            TSTree tree = parser.parseString(null, source);
            if (tree == null) {
                log.warn("Tree-sitter 解析返回 null: {}", filePath);
                return List.of();
            }

            TSNode root = tree.getRootNode();
            List<CodeUnit> units = new ArrayList<>();
            extractMethods(root, source, filePath, language, units);
            return units;

        } catch (Exception e) {
            log.warn("Tree-sitter 解析失败: {} ({}), error: {}", filePath, language, e.getMessage());
            return List.of();
        }
    }

    /**
     * 递归遍历 AST，提取所有方法定义。
     */
    private void extractMethods(TSNode node, String source, String filePath,
                                 String language, List<CodeUnit> result) {
        String[] methodTypes = LanguageMapping.METHOD_NODE_TYPES.get(language);
        if (methodTypes == null) return;

        String nodeType = node.getType();

        // 检查当前节点是否是方法定义
        for (String methodType : methodTypes) {
            if (methodType.equals(nodeType)) {
                CodeUnit unit = buildCodeUnit(node, source, filePath, language);
                if (unit != null) {
                    result.add(unit);
                }
                break;
            }
        }

        // 递归遍历子节点
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull()) {
                extractMethods(child, source, filePath, language, result);
            }
        }
    }

    /**
     * 从 TSNode 构建 CodeUnit。
     */
    private CodeUnit buildCodeUnit(TSNode node, String source, String filePath, String language) {
        try {
            int startByte = node.getStartByte();
            int endByte = node.getEndByte();

            if (startByte < 0 || endByte > source.length() || startByte >= endByte) {
                return null;
            }

            String body = source.substring(startByte, endByte);
            String signature = extractSignature(node, source, language);
            String comment = extractComment(node, source, language);
            List<String> calls = extractCalls(node, source, language);
            String className = extractClassName(node, source, language);

            CodeUnit unit = CodeUnit.builder()
                    .filePath(filePath)
                    .language(language)
                    .className(className)
                    .methodName(extractMethodName(node, source, language))
                    .signature(signature.isBlank() ? body.lines().findFirst().orElse("").trim() : signature)
                    .comment(comment)
                    .body(body)
                    .startLine(node.getStartPoint().getRow() + 1)  // Tree-sitter 是 0-indexed
                    .endLine(node.getEndPoint().getRow() + 1)
                    .calls(calls)
                    .checksum(md5(body))
                    .build();

            return unit;

        } catch (Exception e) {
            log.debug("构建 CodeUnit 失败: {} nodeType={}", filePath, node.getType(), e);
            return null;
        }
    }

    /**
     * 提取方法名。
     */
    private String extractMethodName(TSNode node, String source, String language) {
        // 不同语言的方法名在 AST 中的位置不同
        if ("java".equals(language)) {
            // Java: method_declaration → 第一个 identifier 子节点
            for (int i = 0; i < node.getChildCount(); i++) {
                TSNode child = node.getChild(i);
                if (child != null && "identifier".equals(child.getType())) {
                    return substring(source, child.getStartByte(), child.getEndByte());
                }
            }
        } else if ("go".equals(language)) {
            // Go: function_declaration → name 子节点
            TSNode name = node.getChildByFieldName("name");
            if (name != null) {
                return substring(source, name.getStartByte(), name.getEndByte());
            }
        }
        // fallback：从 body 中取第一行的第一个词
        String firstLine = source.substring(node.getStartByte(),
                Math.min(node.getStartByte() + 200, source.length())).lines().findFirst().orElse("");
        return firstLine.trim().split("\\s+")[0];
    }

    /**
     * 提取方法签名（取第一行或前两行）。
     */
    private String extractSignature(TSNode node, String source, String language) {
        if ("java".equals(language)) {
            // Java 签名在第一行（public/private 开头），取到 '{' 或行尾
            int end = Math.min(node.getStartByte() + 300, source.length());
            String head = source.substring(node.getStartByte(), end);
            int brace = head.indexOf('{');
            return brace > 0 ? head.substring(0, brace).trim() : head.lines().findFirst().orElse("").trim();
        }
        return "";
    }

    /**
     * 提取方法前的注释。
     */
    private String extractComment(TSNode node, String source, String language) {
        TSNode prev = node.getPrevSibling();
        if (prev != null) {
            String[] commentTypes = LanguageMapping.COMMENT_NODE_TYPES.get(language);
            if (commentTypes != null) {
                for (String ct : commentTypes) {
                    if (ct.equals(prev.getType())) {
                        return substring(source, prev.getStartByte(), prev.getEndByte());
                    }
                }
            }
        }
        // 也检查父节点的 pre-comment（JavaParser 风格的前置注释）
        TSNode parent = node.getParent();
        if (parent != null) {
            for (int i = 0; i < parent.getChildCount(); i++) {
                TSNode child = parent.getChild(i);
                if (child != null && child.getStartByte() < node.getStartByte()) {
                    String[] commentTypes = LanguageMapping.COMMENT_NODE_TYPES.get(language);
                    if (commentTypes != null) {
                        for (String ct : commentTypes) {
                            if (ct.equals(child.getType())) {
                                return substring(source, child.getStartByte(), child.getEndByte());
                            }
                        }
                    }
                }
            }
        }
        return "";
    }

    /**
     * 提取方法内部的方法调用列表（方法名级别）。
     */
    private List<String> extractCalls(TSNode node, String source, String language) {
        List<String> calls = new ArrayList<>();
        String[] callTypes = LanguageMapping.CALL_NODE_TYPES.get(language);
        if (callTypes == null) return calls;

        collectCalls(node, source, callTypes, calls);
        return calls;
    }

    private void collectCalls(TSNode node, String source, String[] callTypes, List<String> result) {
        String nodeType = node.getType();
        for (String ct : callTypes) {
            if (ct.equals(nodeType)) {
                // 取第一个 identifier 子节点作为方法名
                for (int i = 0; i < node.getChildCount(); i++) {
                    TSNode child = node.getChild(i);
                    if (child != null && "identifier".equals(child.getType())) {
                        result.add(substring(source, child.getStartByte(), child.getEndByte()));
                        break;
                    }
                }
                break;
            }
        }

        // 递归子节点
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull()) {
                collectCalls(child, source, callTypes, result);
            }
        }
    }

    /**
     * 提取类名。
     */
    private String extractClassName(TSNode node, String source, String language) {
        TSNode parent = node.getParent();
        while (parent != null && !parent.isNull()) {
            String type = parent.getType();
            if ("java".equals(language) && "class_declaration".equals(type)) {
                TSNode name = parent.getChildByFieldName("name");
                if (name != null) return substring(source, name.getStartByte(), name.getEndByte());
            }
            if ("go".equals(language) && ("method_declaration".equals(type) || "function_declaration".equals(type))) {
                // Go 中方法由 receiver 确定类
                TSNode receiver = parent.getChildByFieldName("receiver");
                if (receiver != null) return substring(source, receiver.getStartByte(), receiver.getEndByte());
            }
            parent = parent.getParent();
        }
        return null;
    }

    // ======================== 工具方法 ========================

    private String substring(String source, int start, int end) {
        if (start < 0 || end > source.length() || start >= end) return "";
        return source.substring(start, end);
    }

    private String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(text.hashCode());
        }
    }

    /**
     * 获取或创建 TSParser 实例（按语言缓存）。
     */
    private TSParser getOrCreateParser(String language) {
        return parserCache.computeIfAbsent(language, lang -> {
            String className = LanguageMapping.LANGUAGE_CLASSES.get(lang);
            if (className == null) return null;

            try {
                Class<?> langClass = Class.forName(className);
                TSParser parser = new TSParser();

                Object langInstance = langClass.getDeclaredConstructor().newInstance();
                if (langInstance instanceof org.treesitter.TSLanguage tsLang) {
                    parser.setLanguage(tsLang);
                    log.debug("Tree-sitter parser 已创建: language={}", lang);
                    return parser;
                } else {
                    log.warn("类 {} 不是一个 TSLanguage 实现", className);
                    return null;
                }
            } catch (ClassNotFoundException e) {
                log.warn("Tree-sitter 语言包未找到: {} (pom.xml 未添加依赖?)", className);
                return null;
            } catch (Exception e) {
                log.warn("Tree-sitter parser 创建失败: language={}", lang, e);
                return null;
            }
        });
    }
}
