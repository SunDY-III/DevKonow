package com.zhishu.codeindex;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LanguageEnhancer 插件注册表。
 *
 * <p>Spring 启动时自动收集所有 {@link LanguageEnhancer} 实现类。
 * {@link CodeParser} 在 Tree-sitter 解析后查询此注册表，
 * 有插件则调用增强，无插件则直接返回 Tree-sitter 结果。
 *
 * <p>使用 {@link @Lazy} 加载，避免 enhancer 实现类里的复杂初始化
 * （如 JavaParser 的符号解析器）影响应用启动速度。
 */
@Slf4j
@Component
public class LanguageEnhancerRegistry {

    private final Map<String, LanguageEnhancer> enhancerMap = new HashMap<>();

    /**
     * Spring 注入所有 LanguageEnhancer 实现。
     * 使用 @Lazy 避免每个 enhancer 在启动时立即初始化。
     */
    public LanguageEnhancerRegistry(@Lazy List<LanguageEnhancer> enhancers) {
        for (LanguageEnhancer e : enhancers) {
            String lang = e.supportedLanguage();
            if (enhancerMap.containsKey(lang)) {
                log.warn("检测到重复的 LanguageEnhancer for language '{}': {} vs {}，使用后者",
                        lang, enhancerMap.get(lang).getClass().getSimpleName(), e.getClass().getSimpleName());
            }
            enhancerMap.put(lang, e);
            log.info("LanguageEnhancer 已注册: {} -> {}", lang, e.getClass().getSimpleName());
        }
    }

    @PostConstruct
    void logStatus() {
        if (enhancerMap.isEmpty()) {
            log.warn("LanguageEnhancer 注册表为空：未注册任何精度增强插件。");
            log.warn("所有语言将使用 Tree-sitter 语法级结果，调用链为方法名级别。");
        } else {
            log.info("LanguageEnhancer 注册完成，已覆盖语言: {}", enhancerMap.keySet());
        }
    }

    /**
     * 获取指定语言的增强插件。
     *
     * @param language 语言标识
     * @return 增强插件，如果没有则返回 null（调用方降级到 Tree-sitter 基础结果）
     */
    public LanguageEnhancer get(String language) {
        return enhancerMap.get(language);
    }

    /**
     * 检查指定语言是否有增强插件。
     */
    public boolean hasEnhancer(String language) {
        return enhancerMap.containsKey(language);
    }

    /**
     * 返回所有已注册的语言集合（有精度增强插件的语言）。
     */
    public Set<String> supportedLanguages() {
        return Collections.unmodifiableSet(enhancerMap.keySet());
    }
}
