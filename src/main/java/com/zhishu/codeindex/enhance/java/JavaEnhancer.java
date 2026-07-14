package com.zhishu.codeindex.enhance.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.zhishu.codeindex.CodeUnit;
import com.zhishu.codeindex.LanguageEnhancer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Java 语言精度增强插件。
 *
 * <p>使用 JavaParser + SymbolSolver 对 Tree-sitter 的基础解析结果做类型解析补偿：
 * <ul>
 *   <li>将"方法名级别"的调用链增强为"类.方法(参数)"级别的精确调用链</li>
 *   <li>填充方法所属类型的全限定名</li>
 *   <li>补充注解信息</li>
 * </ul>
 *
 * <p>此插件通过 {@link com.zhishu.codeindex.LanguageEnhancerRegistry} 自动注册。
 * 需要 Java 源码时可从 filePath 推导项目根目录，用于跨文件类型解析。
 */
@Slf4j
@Component
public class JavaEnhancer implements LanguageEnhancer {

    private final JavaTypeResolver typeResolver;
    private final JavaCallChainResolver callChainResolver;

    public JavaEnhancer() {
        // Phase 1：不依赖 Git 仓库路径，使用空源码根目录（仅 JDK 类型可解析）
        // Phase 2 接入 Git 后，从 projectId 获取源码根目录
        this.typeResolver = new JavaTypeResolver(null);
        this.callChainResolver = new JavaCallChainResolver();
    }

    @Override
    public String supportedLanguage() {
        return "java";
    }

    @Override
    public List<CodeUnit> enhance(String filePath, List<CodeUnit> basicUnits) {
        if (basicUnits == null || basicUnits.isEmpty()) {
            return basicUnits;
        }

        // 读取源码内容（从第一个 CodeUnit 的 body 所在的文件推断）
        String sourceCode = readSourceFromUnits(filePath, basicUnits);
        if (sourceCode == null) {
            log.debug("JavaEnhancer: 无法读取源码: {}", filePath);
            return basicUnits;
        }

        // 使用 JavaParser 解析
        Optional<CompilationUnit> cuOpt = typeResolver.getCompilationUnit(filePath, sourceCode);
        if (cuOpt.isEmpty()) {
            return basicUnits;  // JavaParser 解析失败 → 返回 Tree-sitter 基础结果
        }

        CompilationUnit cu = cuOpt.get();

        // 获取包名和类名
        String packageName = cu.getPackageDeclaration()
                .map(p -> p.getNameAsString())
                .orElse(null);

        String className = cu.getPrimaryTypeName().orElse(null);

        // 为每个 CodeUnit 做精度增强
        List<CodeUnit> enhanced = new ArrayList<>(basicUnits.size());
        for (CodeUnit unit : basicUnits) {
            enhanceUnit(unit, cu, packageName, className, filePath);
            enhanced.add(unit);
        }

        log.debug("JavaEnhancer: {} → {} methods enhanced", filePath, enhanced.size());
        return enhanced;
    }

    /**
     * 增强单个 CodeUnit：填充精确调用链、类型全名、注解。
     */
    private void enhanceUnit(CodeUnit unit, CompilationUnit cu,
                              String packageName, String className, String filePath) {
        // 1. 填充包名和类名
        unit.setPackageName(packageName);
        if (unit.getClassName() == null) {
            unit.setClassName(className);
        }

        // 2. 填充类型的全限定名
        if (packageName != null && className != null) {
            unit.setResolvedType(packageName + "." + className);
        }

        // 3. 获取精确调用链
        List<String> enrichedCalls = callChainResolver.resolveCallChain(
                Optional.of(cu),
                unit.getMethodName(),
                unit.getStartLine()
        );
        if (!enrichedCalls.isEmpty()) {
            unit.setEnrichedCalls(enrichedCalls);
        }

        // 4. 补充注解信息
        unit.setAnnotations(extractAnnotations(cu, unit.getMethodName(), unit.getStartLine()));
    }

    /**
     * 提取方法上的注解。
     */
    private List<String> extractAnnotations(CompilationUnit cu, String methodName, int lineNumber) {
        List<String> annotations = new ArrayList<>();
        List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
        for (MethodDeclaration m : methods) {
            if (m.getNameAsString().equals(methodName)) {
                if (m.getBegin().isPresent() && m.getBegin().get().line == lineNumber) {
                    for (var ann : m.getAnnotations()) {
                        annotations.add("@" + ann.getNameAsString());
                    }
                    break;
                }
            }
        }
        return annotations;
    }

    /**
     * 从 CodeUnit 列表和文件路径读取原始源码。
     * Phase 1：从 CodeUnit 的 body 中拼接（此时源码在内存中）。
     * Phase 2：从文件系统读取。
     */
    private String readSourceFromUnits(String filePath, List<CodeUnit> units) {
        // Phase 1 简易实现：从 units 中推断源码内容
        // 在实际索引中，源码由调用方传入，此处为兼容性 fallback
        if (units.isEmpty()) return null;

        // 尝试从第一个 unit 的 body 推断（在内存索引时可用）
        CodeUnit first = units.get(0);
        if (first.getBody() != null && !first.getBody().isBlank()) {
            // 尝试从文件系统读取
            try {
                Path path = Paths.get(filePath);
                if (path.toFile().exists()) {
                    return java.nio.file.Files.readString(path);
                }
            } catch (Exception e) {
                log.debug("JavaEnhancer: 无法从文件系统读取: {}", filePath);
            }
        }
        return null;
    }
}
