package com.devknow.rag.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 评估数据集 —— 从 JSON 文件加载 {@link EvalSample} 列表。
 *
 * <p>支持从 classpath 加载（{@code data/eval-dataset.json}），
 * 也支持通过 API 动态添加样本。
 */
@Slf4j
@Component
public class EvalDataset {

    private final ObjectMapper objectMapper;

    /** 内置数据集 */
    private List<EvalSample> builtinSamples = List.of();

    /** 自定义样本（API 添加） */
    private final List<EvalSample> customSamples = new ArrayList<>();

    @Value("classpath:data/eval-dataset.json")
    private Resource builtinResource;

    public EvalDataset(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        loadBuiltin(builtinResource);
    }

    /**
     * 从 classpath JSON 加载内置数据集。
     * 文件位置：{@code classpath:data/eval-dataset.json}
     */
    public void loadBuiltin(Resource resource) {
        if (resource == null || !resource.exists()) {
            log.info("No built-in eval dataset found, skipping");
            return;
        }
        try (InputStream is = resource.getInputStream()) {
            builtinSamples = objectMapper.readValue(
                    is, new TypeReference<List<EvalSample>>() {});
            log.info("Loaded {} eval samples from {}", builtinSamples.size(), resource.getFilename());
        } catch (Exception e) {
            log.warn("Failed to load eval dataset: {}", e.getMessage());
            builtinSamples = List.of();
        }
    }

    /**
     * 获取所有样本（内置 + 自定义）。
     */
    public List<EvalSample> getAll() {
        List<EvalSample> all = new ArrayList<>(builtinSamples);
        synchronized (customSamples) {
            all.addAll(customSamples);
        }
        return all;
    }

    /**
     * 获取样本总数。
     */
    public int size() {
        return builtinSamples.size() + customSamples.size();
    }

    /**
     * 动态添加一条样本。
     */
    public void addSample(EvalSample sample) {
        synchronized (customSamples) {
            customSamples.add(sample);
        }
    }

    /**
     * 清空自定义样本。
     */
    public void clearCustom() {
        synchronized (customSamples) {
            customSamples.clear();
        }
    }
}
