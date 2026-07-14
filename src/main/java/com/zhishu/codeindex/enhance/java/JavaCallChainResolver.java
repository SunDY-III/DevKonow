package com.zhishu.codeindex.enhance.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Java 精确调用链解析器。
 *
 * <p>基于 JavaParser 的类型解析能力，将 Tree-sitter 提取的"方法名级别"调用链
 * 增强为"类.方法(参数类型)"级别的精确调用链。
 *
 * <p>示例：
 * <ul>
 *   <li>Tree-sitter 提取：["validate", "pay", "info"]</li>
 *   <li>JavaEnhancer 增强后：["com.trade.service.OrderValidator.validate(com.trade.model.CreateReq)",
 *       "com.trade.service.PaymentService.pay(java.lang.Long)",
 *       "com.trade.util.LogUtil.info(java.lang.String)"]</li>
 * </ul>
 */
@Slf4j
public class JavaCallChainResolver {

    public JavaCallChainResolver() {
    }

    /**
     * 解析指定文件中某个方法的精确调用链。
     *
     * @param compilationUnit 文件的 CompilationUnit
     * @param methodName  方法名
     * @param lineNumber  方法的起始行号（用于区分重载）
     * @return 精确调用链列表（类.方法(参数)全限定名）；解析失败时返回方法名列表
     */
    public List<String> resolveCallChain(Optional<CompilationUnit> compilationUnit,
                                           String methodName, int lineNumber) {
        if (compilationUnit.isEmpty()) {
            return List.of();
        }

        MethodDeclaration methodDecl = findMethodDeclaration(compilationUnit.get(), methodName, lineNumber);
        if (methodDecl == null) {
            return List.of();
        }

        List<String> enrichedCalls = new ArrayList<>();
        for (var call : methodDecl.findAll(MethodCallExpr.class)) {
            try {
                var resolved = call.resolve();
                String qualifiedName = resolved.getQualifiedName();

                // 附加参数类型信息增强可读性
                StringBuilder sb = new StringBuilder(qualifiedName);
                sb.append('(');
                int paramCount = resolved.getNumberOfParams();
                for (int i = 0; i < paramCount; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(resolved.getParam(i).getType().describe());
                }
                sb.append(')');
                enrichedCalls.add(sb.toString());
            } catch (Exception e) {
                // 类型解析失败（如外部依赖不可解析）：保留方法名作为降级
                enrichedCalls.add(call.getNameAsString());
            }
        }
        return enrichedCalls;
    }

    /**
     * 在 CompilationUnit 中按方法名 + 行号定位 MethodDeclaration。
     */
    private MethodDeclaration findMethodDeclaration(CompilationUnit cu, String methodName, int lineNumber) {
        List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
        for (MethodDeclaration m : methods) {
            if (m.getNameAsString().equals(methodName)) {
                if (lineNumber <= 0) return m;  // 无行号约束，返回第一个匹配
                if (m.getBegin().isPresent() && m.getBegin().get().line == lineNumber) {
                    return m;  // 方法名 + 行号精确匹配
                }
            }
        }
        return null;
    }

    /**
     * 获取方法的完整签名（含参数类型和返回类型）。
     */
    public String getFullSignature(MethodDeclaration method) {
        try {
            var resolved = method.resolve();
            return resolved.getQualifiedSignature();
        } catch (Exception e) {
            return method.getDeclarationAsString(false, false, true);
        }
    }
}
