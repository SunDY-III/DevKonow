package com.devknow.rag.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 参数快照管理 —— 记录每次评估实验的参数+结果，支持版本回退。
 *
 * <p>Redis 存储结构：
 * <ul>
 *   <li>{@code param:snapshot:{label}} — JSON 格式的评估结果（含参数+指标）</li>
 *   <li>{@code param:snapshots} — 有序集合，按时间戳排序的所有快照标签</li>
 * </ul>
 */
@Slf4j
@Component
public class ParamSnapshot {

    private static final String SNAPSHOT_KEY_PREFIX = "param:snapshot:";
    private static final String SNAPSHOT_INDEX = "param:snapshots";
    private static final int MAX_SNAPSHOTS = 50;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ParamSnapshot(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 保存一次评估结果到快照。
     */
    public void save(RagEvaluator.EvalResult result) {
        try {
            String label = result.label() != null ? result.label() : "eval-" + System.currentTimeMillis();
            String json = objectMapper.writeValueAsString(result);
            redis.opsForValue().set(SNAPSHOT_KEY_PREFIX + label, json, Duration.ofDays(30));
            redis.opsForZSet().add(SNAPSHOT_INDEX, label, System.currentTimeMillis());

            // 限制快照数量
            Long count = redis.opsForZSet().size(SNAPSHOT_INDEX);
            if (count != null && count > MAX_SNAPSHOTS) {
                Set<String> oldest = redis.opsForZSet().range(SNAPSHOT_INDEX, 0, count - MAX_SNAPSHOTS - 1);
                if (oldest != null) {
                    redis.delete(oldest.stream().map(k -> SNAPSHOT_KEY_PREFIX + k).collect(Collectors.toList()));
                    redis.opsForZSet().remove(SNAPSHOT_INDEX, oldest.toArray());
                }
            }

            log.info("ParamSnapshot saved: label={}", label);
        } catch (Exception e) {
            log.warn("Failed to save param snapshot", e);
        }
    }

    /**
     * 获取所有快照标签（按时间降序）。
     */
    public List<String> listSnapshots() {
        Set<String> labels = redis.opsForZSet().reverseRange(SNAPSHOT_INDEX, 0, -1);
        return labels != null ? new ArrayList<>(labels) : List.of();
    }

    /**
     * 获取指定快照。
     */
    public RagEvaluator.EvalResult get(String label) {
        try {
            String json = redis.opsForValue().get(SNAPSHOT_KEY_PREFIX + label);
            if (json == null) return null;
            return objectMapper.readValue(json, RagEvaluator.EvalResult.class);
        } catch (Exception e) {
            log.warn("Failed to get snapshot: {}", label, e);
            return null;
        }
    }

    /**
     * 获取最佳快照（按 compositeScore 排序）。
     */
    public Optional<RagEvaluator.EvalResult> getBest() {
        List<String> labels = listSnapshots();
        return labels.stream()
                .map(this::get)
                .filter(Objects::nonNull)
                .max(Comparator.comparingDouble(RagEvaluator.EvalResult::compositeScore));
    }
}
