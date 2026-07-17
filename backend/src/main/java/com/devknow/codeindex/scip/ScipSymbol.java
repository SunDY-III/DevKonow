package com.devknow.codeindex.scip;

/**
 * SCIP 符号字符串解析器。
 *
 * <p>SCIP 符号格式：{scheme} {package_encoded} {descriptor}
 * 其中 descriptor 用 # 分割层级，用 . 分割同级成员。
 *
 * <p>示例：
 * <pre>
 * scip-java com.example.OrderService#createOrder(CreateReq)
 * scip-java com.example.OrderService#main(java.lang.String[]).execute()
 * scip-go github.com/user/repo.(*Handler).ServeHTTP
 * scip-python module.Class.method
 * </pre>
 */
public class ScipSymbol {

    private final String raw;
    private final String packageName;
    private final String className;
    private final String memberName;

    public ScipSymbol(String raw, String packageName, String className, String memberName) {
        this.raw = raw;
        this.packageName = packageName;
        this.className = className;
        this.memberName = memberName;
    }

    public String getPackageName() { return packageName; }
    public String getClassName() { return className; }
    public String getMemberName() { return memberName; }

    /** 获取类的全限定名：package.ClassName */
    public String getQualifiedClass() {
        if (packageName.isEmpty()) return className;
        return packageName + "." + className;
    }

    /**
     * 解析 SCIP 符号字符串。
     * 支持主流语言的符号格式。
     */
    public static ScipSymbol parse(String symbol) {
        if (symbol == null || symbol.isEmpty()) return null;

        try {
            // 去掉 scheme 前缀（如 "scip-java "）
            String rest = symbol;
            int spaceIdx = symbol.indexOf(' ');
            if (spaceIdx > 0) {
                rest = symbol.substring(spaceIdx + 1).trim();
            }

            // 分离 package 和 descriptor
            // 格式：package#class.member 或 package.class#member 或 package#class#member
            String pkg = "";
            String cls = "";
            String member = "";

            // 按 # 分割获取层级链
            int hashIdx = rest.indexOf('#');
            if (hashIdx < 0) {
                // 无 # → 可能是简单符号，整体作为成员名
                int lastDot = rest.lastIndexOf('.');
                if (lastDot > 0) {
                    cls = rest.substring(0, lastDot);
                    member = rest.substring(lastDot + 1);
                } else {
                    member = rest;
                }
                return new ScipSymbol(symbol, pkg, cls, member);
            }

            // # 之前是 package
            pkg = rest.substring(0, hashIdx).replace("/", ".");
            String descriptorPart = rest.substring(hashIdx + 1);

            // descriptor 部分可能是 "ClassName.method" 或嵌套 "ClassName.method.submethod"
            // 或者是 "ClassName"（仅类符号）
            if (!descriptorPart.isEmpty()) {
                String[] parts = descriptorPart.split("\\.");
                if (parts.length >= 2) {
                    cls = parts[0];
                    // 移除可能的形参 "(...)" 标记
                    int parenIdx = cls.indexOf('(');
                    if (parenIdx > 0) cls = cls.substring(0, parenIdx);
                    member = parts[1];
                    // 提取方法名（去掉参数）
                    int mParenIdx = member.indexOf('(');
                    if (mParenIdx > 0) member = member.substring(0, mParenIdx);
                } else {
                    cls = descriptorPart;
                }
            }

            return new ScipSymbol(symbol, pkg, cls, member);
        } catch (Exception e) {
            // 解析失败时返回 null，调用方做 fallback
            return null;
        }
    }
}
