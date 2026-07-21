package com.devknow.codeindex.extract;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码实体提取器。
 *
 * <p>从用户自然语言问题中提取代码实体信息（方法名、类名、调用意图等），
 * 用于方法级检索的意图分类和路由决策。
 */
@Slf4j
@Component
public class CodeEntityExtractor {

    /** CamelCase 拆分正则 */
    private static final Pattern CAMEL_SPLIT = Pattern.compile("[a-z]+|[A-Z][a-z]*|[A-Z]+(?=[A-Z]|$)");

    /** 方法调用正则：methodName( */
    private static final Pattern METHOD_CALL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(");

    /** 点号链正则：ClassName.methodName */
    private static final Pattern DOT_CHAIN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+");

    /**
     * 分类用户查询意图，用于方法级检索路由决策。
     *
     * <p>返回结果：
     * <ul>
     *   <li>{@code METHOD_LOOKUP} — 查找方法定义/签名/参数</li>
     *   <li>{@code CALL_CHAIN} — 查询调用链/谁调了谁</li>
     *   <li>{@code IMPLEMENTATION} — 查询实现细节/算法</li>
     *   <li>{@code UNKNOWN} — 无法分类</li>
     * </ul>
     */
    public Intent classifyIntent(String question) {
        if (question == null || question.isBlank()) {
            return Intent.UNKNOWN;
        }
        String lower = question.toLowerCase();

        // 调用链关键词
        if (lower.contains("调用链") || lower.contains("谁调") || lower.contains("call chain")
                || lower.contains("被谁") || lower.contains("调用关系") || lower.contains("依赖链")
                || lower.contains("影响分析") || lower.contains("trace") || lower.contains("call graph")
                || lower.contains("调用了哪些") || lower.contains("depend")) {
            return Intent.CALL_CHAIN;
        }

        // 方法定义/签名关键词
        if (lower.contains("方法") || lower.contains("签名") || lower.contains("参数")
                || lower.contains("返回值") || lower.contains("参数类型") || lower.contains("方法名")
                || lower.contains("在哪里") || lower.contains("where is") || lower.contains("definition")
                || lower.contains("signature") || lower.contains("method")) {
            // 如果同时有方法调用语法，优先归为 CALL_CHAIN
            Matcher m = METHOD_CALL.matcher(question);
            if (m.find()) {
                return Intent.METHOD_LOOKUP;
            }
            // 点号链通常指方法调用
            if (DOT_CHAIN.matcher(question).find()) {
                return Intent.CALL_CHAIN;
            }
            return Intent.METHOD_LOOKUP;
        }

        // 代码实现/算法关键词
        if (lower.contains("实现") || lower.contains("如何") || lower.contains("怎么")
                || lower.contains("原理") || lower.contains("逻辑") || lower.contains("算法")
                || lower.contains("流程") || lower.contains("原因") || lower.contains("为何")
                || lower.contains("implementation") || lower.contains("how does")
                || lower.contains("logic") || lower.contains("algorithm")) {
            return Intent.IMPLEMENTATION;
        }

        // 检查是否有方法名模式
        Matcher parenMatcher = METHOD_CALL.matcher(question);
        if (parenMatcher.find()) {
            return Intent.METHOD_LOOKUP;
        }

        return Intent.UNKNOWN;
    }

    /**
     * 从查询中提取类名。
     *
     * <p>优先尝试识别 CamelCase 类名模式（首字母大写后跟小写字母）。
     * 从点号链如 "OrderService.createOrder" 中提取 "OrderService"。
     */
    public String extractClassName(String question) {
        if (question == null || question.isBlank()) return null;

        Matcher dotMatcher = DOT_CHAIN.matcher(question);
        if (dotMatcher.find()) {
            String full = dotMatcher.group();
            int dot = full.indexOf('.');
            return dot > 0 ? full.substring(0, dot) : null;
        }

        // 尝试识别首字母大写的单词作为类名
        Matcher camelMatcher = CAMEL_SPLIT.matcher(question);
        while (camelMatcher.find()) {
            String t = camelMatcher.group();
            if (!t.isEmpty() && Character.isUpperCase(t.charAt(0))) {
                return t;
            }
        }
        return null;
    }

    /**
     * 从查询中提取方法名。
     */
    public String extractMethodName(String question) {
        if (question == null || question.isBlank()) return null;

        // 优先匹配 methodName( 模式
        Matcher parenMatcher = METHOD_CALL.matcher(question);
        if (parenMatcher.find()) {
            return parenMatcher.group(1);
        }

        // 从点号链取最后一段
        Matcher dotMatcher = DOT_CHAIN.matcher(question);
        if (dotMatcher.find()) {
            String full = dotMatcher.group();
            int dot = full.lastIndexOf('.');
            return dot >= 0 ? full.substring(dot + 1) : full;
        }

        return null;
    }

    /** 查询意图枚举 */
    public enum Intent {
        METHOD_LOOKUP,    // 方法定义/签名查找
        CALL_CHAIN,       // 调用链查询
        IMPLEMENTATION,   // 实现细节
        UNKNOWN           // 无法分类
    }
}
