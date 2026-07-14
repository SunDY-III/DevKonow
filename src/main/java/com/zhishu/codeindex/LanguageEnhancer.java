package com.zhishu.codeindex;

import java.util.List;

/**
 * LanguageEnhancer 插件接口。
 *
 * <p>实现此接口并注册为 Spring Bean，自动被 {@link LanguageEnhancerRegistry} 收集。
 * Tree-sitter 对各语言完成统一的语法级解析后，CodeParser 会查询注册表，
 * 为当前语言调用对应的增强插件做精度补偿（如类型解析、import 绑定、精确调用链）。
 *
 * <p>没有插件的语言天然降级到 Tree-sitter 语法级结果，系统不受影响。
 * 每加一种语言的精度增强，只需要新建一个实现此接口的 .java 文件。
 *
 * <p>接口定位（面试点）：
 * <ol>
 *   <li>Tree-sitter 做统一语法基准层（100+ 语言，方法名/行号/注释）</li>
 *   <li>LanguageEnhancer 做精度补偿层（类型解析、调用链增强）</li>
 *   <li>无插件 = 降级非报错</li>
 * </ol>
 */
public interface LanguageEnhancer {

    /**
     * 返回此插件支持的语言标识，如 "java"、"python"、"go"。
     * 该标识与 {@link LanguageMapping} 中的语言名一致。
     */
    String supportedLanguage();

    /**
     * 增强 Tree-sitter 提取的基础 CodeUnit 列表。
     * <p>
     * 实现类在此方法中对 basicUnits 补充类型信息：
     * <ul>
     *   <li>{@link CodeUnit#setEnrichedCalls(List)} — 精确调用链（类.方法级别）</li>
     *   <li>{@link CodeUnit#setResolvedType(String)} — 当前方法所属的类型全名</li>
     *   <li>{@link CodeUnit#setAnnotations(List)} — 注解信息（字符串级别）</li>
     * </ul>
     *
     * @param filePath   源码文件路径（可用于读取源码或确定项目根目录）
     * @param basicUnits Tree-sitter 提取的基础 CodeUnit 列表（方法粒度）
     * @return 增强后的 CodeUnit 列表（精度更高的调用链 / 类型信息）
     */
    List<CodeUnit> enhance(String filePath, List<CodeUnit> basicUnits);
}
