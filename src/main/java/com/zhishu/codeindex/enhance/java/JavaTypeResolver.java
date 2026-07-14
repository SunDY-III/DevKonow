package com.zhishu.codeindex.enhance.java;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Java 类型解析器。
 *
 * <p>封装 JavaParser + SymbolSolver，提供：
 * <ul>
 *   <li>方法调用解析 → 全限定名（如 "com.trade.service.OrderService.createOrder"）</li>
 *   <li>类型绑定 → 识别变量/字段的具体类型</li>
 *   <li>import 解析 → 获取类全名</li>
 * </ul>
 *
 * <p>注意：SymbolSolver 需要知道源码路径才能解析跨文件类型。
 * 如果 {@code projectSourceRoot} 未设置，则仅能解析 JDK 内部类型。
 */
@Slf4j
public class JavaTypeResolver {

    private final CombinedTypeSolver combinedSolver;
    private final JavaSymbolSolver symbolSolver;

    /** 文件路径 → CompilationUnit 缓存（同一文件不重复解析） */
    private final Map<String, CompilationUnit> cuCache = new HashMap<>();

    /**
     * @param projectSourceRoot 项目源码根目录（如 "src/main/java"），
     *                          为 null 时不解析项目内类型（仅 JDK 类型可解析）
     */
    public JavaTypeResolver(Path projectSourceRoot) {
        this.combinedSolver = new CombinedTypeSolver();
        combinedSolver.add(new ReflectionTypeSolver());  // JDK 类型

        if (projectSourceRoot != null) {
            File sourceRoot = projectSourceRoot.toFile();
            if (sourceRoot.exists() && sourceRoot.isDirectory()) {
                combinedSolver.add(new JavaParserTypeSolver(sourceRoot));
                log.info("JavaTypeResolver: 项目源码根目录已注册: {}", projectSourceRoot);
            } else {
                log.warn("JavaTypeResolver: 源码根目录不存在: {}", projectSourceRoot);
            }
        }

        this.symbolSolver = new JavaSymbolSolver(combinedSolver);

        // 全局配置 SymbolSolver
        ParserConfiguration config = new ParserConfiguration();
        config.setSymbolResolver(symbolSolver);
        StaticJavaParser.setConfiguration(config);
    }

    /**
     * 解析方法内所有调用的全限定名。
     *
     * @param callerMethod 方法声明
     * @return 方法名 → 全限定名的映射；解析失败的 key 不放入 map
     */
    public Map<String, String> resolveAllCalls(MethodDeclaration callerMethod) {
        Map<String, String> result = new HashMap<>();
        if (callerMethod == null) return result;

        List<MethodCallExpr> calls = callerMethod.findAll(MethodCallExpr.class);
        for (var call : calls) {
            try {
                ResolvedMethodDeclaration resolved = call.resolve();
                result.putIfAbsent(call.getNameAsString(), resolved.getQualifiedName());
            } catch (Exception e) {
                // 解析失败：可能调用的源码不在项目中（如第三方库），跳过
                log.trace("方法调用解析失败: {} (可能为外部依赖)", call.getNameAsString());
            }
        }
        return result;
    }

    /**
     * 解析类型的全限定名。
     *
     * @param typeName 简单类型名（如 "OrderService"）
     * @return 全限定名（如 "com.trade.service.OrderService"），解析失败返回 null
     */
    public String resolveType(String typeName) {
        try {
            var resolved = combinedSolver.solveType(typeName);
            return resolved.getQualifiedName();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取 CompilationUnit（带缓存）。
     */
    public Optional<CompilationUnit> getCompilationUnit(String filePath, String sourceCode) {
        if (cuCache.containsKey(filePath)) {
            return Optional.ofNullable(cuCache.get(filePath));
        }
        try {
            // 使用全局配置（含 SymbolSolver）解析
            CompilationUnit cu = StaticJavaParser.parse(sourceCode);
            cuCache.put(filePath, cu);
            return Optional.of(cu);
        } catch (Exception e) {
            log.warn("JavaParser 解析失败: {}", filePath, e);
            cuCache.put(filePath, null);
            return Optional.empty();
        }
    }

    /**
     * 关闭并清理缓存。
     */
    public void clearCache() {
        cuCache.clear();
    }
}
