package com.devknow.codeindex.tree;

import java.util.Map;

/**
 * 语言 → Tree-sitter {@link org.treesitter.TSLanguage} 映射表。
 *
 * <p>每加一种语言，在此添加一条映射 + pom.xml 加一个 dependency。
 * 所有映射集中于此，便于统一管理。
 */
public class LanguageMapping {

    private LanguageMapping() {}

    /**
     * 语言标识 → Tree-sitter Language 类名（全限定）。
     * key 为小写语言名，与文件后缀对应。
     */
    public static final Map<String, String> LANGUAGE_CLASSES = Map.of(
            "java", "org.treesitter.TreeSitterJava",
            "go", "org.treesitter.TreeSitterGo",
            "javascript", "org.treesitter.TreeSitterJavascript"
            // 后续可扩展：python, typescript, rust, kotlin...
    );

    /**
     * 文件后缀 → 语言标识。
     */
    public static final Map<String, String> EXTENSION_TO_LANGUAGE = Map.of(
            "java", "java",
            "go", "go",
            "js", "javascript",
            "jsx", "javascript",
            "mjs", "javascript"
            // 后续可扩展：py → python, ts → typescript, rs → rust, kt → kotlin...
    );

    /**
     * 各语言中"方法定义"节点的类型名。
     * Tree-sitter 对不同语言使用不同的节点类型名标识方法定义。
     */
    public static final Map<String, String[]> METHOD_NODE_TYPES = Map.of(
            "java", new String[]{"method_declaration", "constructor_declaration"},
            "go", new String[]{"function_declaration", "method_declaration"},
            "javascript", new String[]{"method_definition", "function_declaration", "arrow_function"}
    );

    /**
     * 各语言中"方法调用"节点的类型名。
     */
    public static final Map<String, String[]> CALL_NODE_TYPES = Map.of(
            "java", new String[]{"method_invocation"},
            "go", new String[]{"call_expression"},
            "javascript", new String[]{"call_expression"}
    );

    /**
     * 各语言中"注释"节点的类型名。
     */
    public static final Map<String, String[]> COMMENT_NODE_TYPES = Map.of(
            "java", new String[]{"block_comment", "line_comment"},
            "go", new String[]{"comment"},
            "javascript", new String[]{"comment"}
    );

    /**
     * 根据文件后缀检测语言。
     *
     * @param fileName 文件名（含后缀）
     * @return 语言标识，如果不支持则返回 null
     */
    public static String detectLanguage(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return null;
        String ext = fileName.substring(dot + 1).toLowerCase();
        return EXTENSION_TO_LANGUAGE.get(ext);
    }
}
