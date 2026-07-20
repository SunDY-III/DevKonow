package com.devknow.rag;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 查询同义词扩展。
 * 用户搜"订单" → 自动扩展为 "订单" OR "order" OR "dingdan"
 * 覆盖代码中拼音/缩写/中英文混用的场景，减少对向量检索的依赖。
 *
 * <p>优化：使用双向 Map（正向 + 反向索引），
 * 避免 expand 时逐条遍历全量表进行反向查找（O(n) → O(1)）。
 */
@Slf4j
@Component
public class QueryExpander {

    /** 正向同义词表：原词 → [同义词列表] */
    private final Map<String, List<String>> synonymMap = new HashMap<>();

    /** 反向索引：同义词 → [原词列表]（用于反向查找，如搜 "order" 查到 "订单"） */
    private final Map<String, List<String>> reverseMap = new HashMap<>();

    @Value("classpath:synonyms.yml")
    private Resource synonymsResource;

    @PostConstruct
    void loadSynonyms() {
        try (InputStream is = synonymsResource.getInputStream()) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                line = line.strip();
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    String key = parts[0].strip().toLowerCase();
                    List<String> values = Arrays.stream(parts[1].split(","))
                            .map(String::strip)
                            .filter(s -> !s.isEmpty())
                            .toList();
                    if (!values.isEmpty()) {
                        synonymMap.put(key, values);
                        // 构建反向索引
                        for (String v : values) {
                            reverseMap.computeIfAbsent(v, k -> new ArrayList<>()).add(key);
                        }
                    }
                }
            }
            log.info("同义词加载完成: {} 组, {} 反向条目", synonymMap.size(), reverseMap.size());
        } catch (Exception e) {
            log.warn("同义词加载失败", e);
        }
    }

    /**
     * 扩展查询词：输入返回原文 + 所有同义词。
     * 利用双向 Map 实现 O(1) 正向和反向查找。
     *
     * @param question 用户问题
     * @return 扩展后的查询词列表
     */
    public List<String> expand(String question) {
        if (question == null || question.isBlank()) return List.of();
        Set<String> result = new LinkedHashSet<>();

        // 中英文分词（简单按空格和标点切）
        String[] tokens = question.toLowerCase().split("[\\s,，。；;、．.！!？?（）()【】\\[\\]：:]+");

        for (String token : tokens) {
            if (token.isBlank()) continue;
            result.add(token);
            // 正向查：原词 → 同义词（O(1)）
            List<String> syns = synonymMap.get(token);
            if (syns != null) {
                result.addAll(syns);
            }
            // 反向查：同义词 → 原词（O(1)，使用预构建的反向索引）
            List<String> origins = reverseMap.get(token);
            if (origins != null) {
                result.addAll(origins);
            }
        }

        return new ArrayList<>(result);
    }
}
