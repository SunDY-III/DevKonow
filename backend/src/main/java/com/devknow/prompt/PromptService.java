package com.devknow.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 模板管理服务。
 *
 * <p>LLM prompt 外部化到 prompt_template 表，支持热更新无需重启应用。
 */
@Slf4j
@Service
public class PromptService {

    private final Map<Long, PromptTemplate> store = new ConcurrentHashMap<>();

    public PromptService() {
        // 初始化一些默认模板
        initDefaults();
    }

    /**
     * 获取所有 prompt 模板。
     */
    public List<PromptTemplate> listPrompts() {
        List<PromptTemplate> list = new ArrayList<>(store.values());
        list.sort(Comparator.comparing(PromptTemplate::getId));
        return list;
    }

    /**
     * 获取单个 prompt 模板。
     */
    public PromptTemplate getPrompt(Long id) {
        PromptTemplate pt = store.get(id);
        if (pt == null) {
            throw new IllegalArgumentException("Prompt 模板不存在: " + id);
        }
        return pt;
    }

    /**
     * 更新 prompt 模板。
     * 检查版本号用于并发控制。
     */
    public PromptTemplate updatePrompt(Long id, PromptTemplate update) {
        PromptTemplate existing = store.get(id);
        if (existing == null) {
            throw new IllegalArgumentException("Prompt 模板不存在: " + id);
        }
        if (!existing.getVersion().equals(update.getVersion())) {
            throw new IllegalStateException("版本冲突：预期版本 " + existing.getVersion()
                    + "，收到版本 " + update.getVersion());
        }
        if (update.getContent() != null) existing.setContent(update.getContent());
        if (update.getName() != null) existing.setName(update.getName());
        existing.setVersion(existing.getVersion() + 1);
        existing.setUpdatedAt(new Date());
        store.put(id, existing);
        log.info("更新 Prompt 模板: id={}, type={}, version={}", id, existing.getType(), existing.getVersion());
        return existing;
    }

    private void initDefaults() {
        PromptTemplate t1 = new PromptTemplate();
        t1.setId(1L);
        t1.setType("teaching");
        t1.setName("代码解释");
        t1.setContent("请以导师身份解释以下代码的功能和设计思路：\n```\n{{code}}\n```\n项目上下文：{{context}}");
        t1.setVariables("[\"code\", \"context\"]");
        t1.setVersion(1);
        t1.setUpdatedAt(new Date());
        store.put(1L, t1);

        PromptTemplate t2 = new PromptTemplate();
        t2.setId(2L);
        t2.setType("mentoring");
        t2.setName("护航计划生成");
        t2.setContent("你是一名代码护航导师。根据以下项目上下文生成章节化的学习计划。\n项目：{{projectName}}\n上下文：{{context}}");
        t2.setVariables("[\"projectName\", \"context\"]");
        t2.setVersion(1);
        t2.setUpdatedAt(new Date());
        store.put(2L, t2);

        PromptTemplate t3 = new PromptTemplate();
        t3.setId(3L);
        t3.setType("system");
        t3.setName("系统 Prompt");
        t3.setContent("你是 DevKnow 代码助手，帮助开发者理解和学习代码项目。");
        t3.setVariables("[]");
        t3.setVersion(1);
        t3.setUpdatedAt(new Date());
        store.put(3L, t3);
    }
}
