package com.devknow.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DFA 敏感词过滤器单元测试。
 * 直接构造实例并注入测试词库，不依赖 Spring 上下文。
 */
class SensitiveWordFilterTest {

    private SensitiveWordFilter filter;

    @BeforeEach
    void setUp() {
        filter = new SensitiveWordFilter();
        // 手动添加上线后替换的真实敏感词（与 sensitive-words.txt 一致）
        filter.addWord("api key");
        filter.addWord("secret_key");
        filter.addWord("ghp_");
    }

    @Test
    void containsSensitive_shouldFindExactMatch() {
        assertTrue(filter.containsSensitive("my api key is here"));
    }

    @Test
    void containsSensitive_shouldFindPartialMatch() {
        assertTrue(filter.containsSensitive("token=ghp_xxxxxxxxxxxx"));
    }

    @Test
    void containsSensitive_shouldNotFindCleanText() {
        assertFalse(filter.containsSensitive("how to implement login"));
    }

    @Test
    void replaceSensitive_shouldMaskMatchedWords() {
        String result = filter.replaceSensitive("my secret_key is safe");
        assertTrue(result.contains("***"));
        assertFalse(result.contains("secret_key"));
    }

    @Test
    void replaceSensitive_shouldHandleMultipleMatches() {
        String result = filter.replaceSensitive("api key and secret_key both here");
        assertTrue(result.contains("***"));
        // 两个敏感词应该都被替换
        assertFalse(result.contains("api key"));
        assertFalse(result.contains("secret_key"));
    }

    @Test
    void containsSensitive_shouldHandleEmptyInput() {
        assertFalse(filter.containsSensitive(null));
        assertFalse(filter.containsSensitive(""));
    }

    @Test
    void containsSensitive_shouldHandleCaseSensitivity() {
        // DFA 匹配是大小写敏感的（与敏感词文件中的写法一致）
        assertTrue(filter.containsSensitive("API KEY"));
    }
}
