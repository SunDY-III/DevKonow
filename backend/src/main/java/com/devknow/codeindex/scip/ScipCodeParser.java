package com.devknow.codeindex.scip;

import com.devknow.codeindex.CodeUnit;
import com.devknow.codeindex.scip.proto.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SCIP 模式解析器。
 *
 * <p>直接读取项目目录下的 {@code index.scip}（SCIP protobuf 格式），
 * 提取方法/函数级别的符号定义，构建 CodeUnit 列表。
 * 此模式下不运行 Tree-sitter，两者互斥。
 *
 * <p>需要前置条件：项目根目录存在 {@code index.scip} 文件（由外部 SCIP indexer 生成）。
 */
@Slf4j
@Component
public class ScipCodeParser {

    private final ScipConfig config;

    public ScipCodeParser(ScipConfig config) {
        this.config = config;
    }

    /**
     * 从 SCIP 索引文件解析整个项目的代码单元。
     *
     * @param projectDir 项目根目录
     * @return 项目中所有方法/函数的 CodeUnit 列表，按文件路径分组
     */
    public List<CodeUnit> parseProject(String projectDir) {
        Optional<Path> indexFile = findIndexFile(projectDir);
        if (indexFile.isEmpty()) {
            log.warn("SCIP 模式：未找到索引文件（{}），项目解析结果为空", config.getIndexFileName());
            return List.of();
        }

        try {
            byte[] data = Files.readAllBytes(indexFile.get());
            Index index = Index.parseFrom(data);
            return parseIndex(projectDir, index);
        } catch (IOException e) {
            log.error("SCIP 索引文件读取失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 从 SCIP 索引文件解析单个文件的代码单元。
     *
     * @param projectDir  项目根目录
     * @param filePath    相对文件路径
     * @return 文件中的方法/函数 CodeUnit 列表
     */
    public List<CodeUnit> parseFile(String projectDir, String filePath) {
        Optional<Path> indexFile = findIndexFile(projectDir);
        if (indexFile.isEmpty()) return List.of();

        try {
            byte[] data = Files.readAllBytes(indexFile.get());
            Index index = Index.parseFrom(data);

            // 找到目标文件的 Document
            Document targetDoc = null;
            for (Document doc : index.getDocumentsList()) {
                if (doc.getRelativePath().equals(filePath)) {
                    targetDoc = doc;
                    break;
                }
            }
            if (targetDoc == null) return List.of();

            return parseDocument(projectDir, targetDoc);
        } catch (IOException e) {
            log.warn("SCIP 文件解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== 内部实现 ====================

    private List<CodeUnit> parseIndex(String projectDir, Index index) {
        List<CodeUnit> allUnits = new ArrayList<>();
        for (Document doc : index.getDocumentsList()) {
            try {
                allUnits.addAll(parseDocument(projectDir, doc));
            } catch (Exception e) {
                log.warn("SCIP 解析文档失败: {} - {}", doc.getRelativePath(), e.getMessage());
            }
        }
        log.info("SCIP 解析完成: {} 篇文档, {} 个方法", index.getDocumentsCount(), allUnits.size());
        return allUnits;
    }

    private List<CodeUnit> parseDocument(String projectDir, Document doc) {
        String relativePath = doc.getRelativePath();

        // 读取源码文件（用于 byte offset → line number 转换）
        Path sourceFile = Path.of(projectDir, relativePath);
        String source = "";
        List<Integer> lineOffsets = List.of(0);
        try {
            source = Files.readString(sourceFile, StandardCharsets.UTF_8);
            lineOffsets = buildLineOffsets(source);
        } catch (IOException e) {
            log.debug("SCIP: 无法读取源文件（可能已删除）: {}", relativePath);
        }

        // 构建符号映射表
        Map<String, SymbolInformation> symbolMap = new HashMap<>();
        for (SymbolInformation sym : doc.getSymbolsList()) {
            symbolMap.put(sym.getSymbol(), sym);
        }

        // 收集方法/函数定义
        List<CodeUnit> units = new ArrayList<>();
        for (Occurrence occ : doc.getOccurrencesList()) {
            if (!isDefinition(occ)) continue;

            SymbolInformation sym = symbolMap.get(occ.getSymbol());
            if (sym == null) continue;

            SymbolKind kind = sym.getKind();
            if (kind != SymbolKind.Method && kind != SymbolKind.Function
                    && kind != SymbolKind.Constructor) continue;

            // 解析字节区间 → 行号
            int startLine = 1, endLine = 1;
            if (!source.isEmpty()) {
                int[] range = parseRange(occ.getRange());
                startLine = byteOffsetToLine(lineOffsets, range[0]);
                endLine = Math.max(startLine, byteOffsetToLine(lineOffsets, range[1]));
            }

            // 提取方法名和类名
            ScipSymbol parsed = ScipSymbol.parse(occ.getSymbol());
            String methodName = parsed != null ? parsed.getMemberName() : sym.getSymbol();

            // 构建签名
            String signature = buildSignature(methodName, sym);
            String resolvedType = parsed != null ? parsed.getQualifiedClass() : "";

            // 提取方法体（从源码）
            String body = "";
            if (!source.isEmpty() && startLine > 0 && endLine >= startLine) {
                body = extractBody(source, lineOffsets, startLine, endLine);
            }

            // 收集本方法调用的其他符号（引用 Occurrence）
            List<String> calls = collectCalls(doc, occ, symbolMap);

            CodeUnit unit = CodeUnit.builder()
                    .filePath(relativePath)
                    .language(detectLanguage(relativePath))
                    .packageName(parsed != null ? parsed.getPackageName() : "")
                    .className(parsed != null ? parsed.getClassName() : "")
                    .methodName(methodName)
                    .signature(signature)
                    .body(body)
                    .startLine(startLine)
                    .endLine(endLine)
                    .calls(calls)
                    .enrichedCalls(calls)  // SCIP 的调用链已经是类.方法级别
                    .resolvedType(resolvedType)
                    .build();

            units.add(unit);
        }

        return units;
    }

    /** 判断 Occurrence 是否是符号定义（非引用） */
    private boolean isDefinition(Occurrence occ) {
        return (occ.getSymbolRoles() & 0x04) != 0;  // Definition role = 4
    }

    /** 解析 SCIP range（byte offset 编码：[start, end) 两个 varint） */
    private int[] parseRange(ByteString rangeBytes) {
        try {
            CodedInputStream cis = CodedInputStream.newInstance(rangeBytes.toByteArray());
            int start = cis.readUInt32();
            int end = cis.readUInt32();
            return new int[]{start, end};
        } catch (IOException e) {
            return new int[]{0, 0};
        }
    }

    /** 构建源码行偏移映射表 */
    private List<Integer> buildLineOffsets(String source) {
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                offsets.add(i + 1);
            }
        }
        return offsets;
    }

    /** 字节偏移 → 行号（1-indexed） */
    private int byteOffsetToLine(List<Integer> lineOffsets, int offset) {
        for (int i = lineOffsets.size() - 1; i >= 0; i--) {
            if (lineOffsets.get(i) <= offset) return i + 1;
        }
        return 1;
    }

    /** 从源码提取方法体 */
    private String extractBody(String source, List<Integer> lineOffsets, int startLine, int endLine) {
        if (startLine < 1 || endLine > lineOffsets.size()) return "";
        int start = lineOffsets.get(startLine - 1);
        int end = endLine < lineOffsets.size() ? lineOffsets.get(endLine) : source.length();
        return source.substring(start, Math.min(end, source.length()));
    }

    /** 收集本方法内部调用的其他符号 */
    private List<String> collectCalls(Document doc, Occurrence methodDef,
                                       Map<String, SymbolInformation> symbolMap) {
        int[] methodRange = parseRange(methodDef.getRange());
        int methodStart = methodRange[0];
        int methodEnd = methodRange[1];

        Set<String> calls = new LinkedHashSet<>();
        for (Occurrence occ : doc.getOccurrencesList()) {
            if (isDefinition(occ)) continue;  // 只要引用
            int[] occRange = parseRange(occ.getRange());
            // 只取在方法体范围内的引用
            if (occRange[0] >= methodStart && occRange[1] <= methodEnd) {
                SymbolInformation targetSym = symbolMap.get(occ.getSymbol());
                if (targetSym != null) {
                    ScipSymbol parsed = ScipSymbol.parse(occ.getSymbol());
                    if (parsed != null) {
                        calls.add(parsed.getMemberName());
                    } else {
                        // fallback 到符号的最后一段
                        String sym = occ.getSymbol();
                        int lastDot = sym.lastIndexOf('.');
                        int lastHash = sym.lastIndexOf('#');
                        int idx = Math.max(lastDot, lastHash);
                        calls.add(idx >= 0 ? sym.substring(idx + 1) : sym);
                    }
                }
            }
        }
        return List.copyOf(calls);
    }

    /** 构建方法签名 */
    private String buildSignature(String methodName, SymbolInformation sym) {
        Signature sig = sym.getSignature();
        if (sig == null) return methodName;

        StringBuilder sb = new StringBuilder();
        if (!sig.getMethodReturnType().isEmpty()) {
            sb.append(sig.getMethodReturnType()).append(" ");
        }
        sb.append(methodName).append("(");
        List<String> paramTypes = sig.getMethodParameterTypesList();
        List<String> paramNames = sig.getMethodParameterNamesList();
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(paramTypes.get(i));
            if (i < paramNames.size()) {
                sb.append(" ").append(paramNames.get(i));
            }
        }
        sb.append(")");
        return sb.toString();
    }

    /** 根据文件名检测语言 */
    private String detectLanguage(String filePath) {
        if (filePath == null) return "";
        String lower = filePath.toLowerCase();
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".go")) return "go";
        if (lower.endsWith(".js") || lower.endsWith(".jsx")) return "javascript";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript";
        if (lower.endsWith(".rs")) return "rust";
        if (lower.endsWith(".kt") || lower.endsWith(".kts")) return "kotlin";
        if (lower.endsWith(".scala")) return "scala";
        if (lower.endsWith(".rb")) return "ruby";
        if (lower.endsWith(".php")) return "php";
        return "";
    }

    /** 查找 index.scip 文件 */
    public Optional<Path> findIndexFile(String projectDir) {
        if (!config.isEnabled()) return Optional.empty();
        Path indexFile = Path.of(projectDir, config.getIndexFileName());
        if (Files.exists(indexFile)) {
            return Optional.of(indexFile);
        }
        return Optional.empty();
    }
}
