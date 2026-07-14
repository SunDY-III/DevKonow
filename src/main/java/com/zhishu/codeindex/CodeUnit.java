package com.zhishu.codeindex;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 代码单元模型。
 *
 * <p>粒度：一个方法/函数 = 一个 CodeUnit。
 * 由 {@link TreeSitterParser} 提取基础信息，{@link LanguageEnhancer} 补充增强信息。
 *
 * <p>两个层次的数据：
 * <ul>
 *   <li>基础层（Tree-sitter 提取）：方法名 / 签名 / 注释 / 行号 / 方法体 / 调用的方法名列表</li>
 *   <li>增强层（LanguageEnhancer 补偿）：精确调用链（类.方法）/ 类型全名 / 注解</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeUnit {

    // ==================== 基础层（Tree-sitter 提取） ====================

    /** 项目 ID（Phase 2.5 启用） */
    private Long projectId;

    /** Git 仓库名 */
    private String repoName;

    /** 源码文件路径，如 "src/main/java/com/trade/OrderService.java" */
    private String filePath;

    /** 语言标识，如 "java"、"python"、"go" */
    private String language;

    /** 包名/模块名，如 "com.trade.service" */
    private String packageName;

    /** 所属类名，如 "OrderService" */
    private String className;

    /** 方法名，如 "createOrder" */
    private String methodName;

    /** 完整方法签名，如 "public OrderVO createOrder(CreateReq req) throws BizException" */
    private String signature;

    /** Javadoc/注释（取第一段摘要） */
    private String comment;

    /** 方法体源码 */
    private String body;

    /** 起始行号（1-indexed） */
    private int startLine;

    /** 结束行号（1-indexed） */
    private int endLine;

    /** 内容哈希（用于差量检测） */
    private String checksum;

    // ==================== 基础调用链（Tree-sitter 提取，方法名级别） ====================

    /**
     * Tree-sitter 提取的调用列表（方法名级别）。
     * 如 ["validate", "pay", "info"]。
     * 在 JavaEnhancer 未启用时作为 fallback。
     */
    private List<String> calls;

    // ==================== 增强层（LanguageEnhancer 补充） ====================

    /**
     * 增强后的精确调用链（类.方法级别）。
     * 如 ["OrderValidator.validate(CreateReq)", "PaymentService.pay(Long)", "LogUtil.info(String)"]。
     * 由 JavaEnhancer（JavaParser 类型解析）填充。
     */
    private List<String> enrichedCalls;

    /**
     * 当前方法所属类型的全限定名。
     * Java 示例："com.trade.service.OrderService"
     */
    private String resolvedType;

    /**
     * 注解列表（字符串级别，如 ["@Override", "@Transactional"]）。
     * 基础层 Tree-sitter 可提取注解名，增强层 JavaEnhancer 可解析全名。
     */
    private List<String> annotations;
}
