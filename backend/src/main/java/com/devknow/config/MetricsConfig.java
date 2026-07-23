package com.devknow.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * 自定义 Micrometer 指标注册 —— 暴露 RAG 管道关键指标到 Prometheus。
 *
 * <p>当前埋点指标：
 * <ul>
 *   <li>{@code devknow.rag.retrieve.timing} — RAG 检索各步骤耗时（向量/关键词/RRF/MMR/CE）</li>
 *   <li>{@code devknow.cache.hits} — 语义缓存命中次数</li>
 *   <li>{@code devknow.cache.misses} — 语义缓存未命中次数</li>
 *   <li>{@code devknow.token.total} — Token 消耗总量（由 TokenAuditService 更新）</li>
 *   <li>{@code devknow.rag.crag.triggered} — CRAG 纠错触发次数</li>
 *   <li>{@code devknow.agent.tool.calls} — ReAct Agent 工具调用次数</li>
 * </ul>
 *
 * <p>使用方式：{@code metricsConfig.getRetrieveTimer().record(() -> doSearch())}
 */
@Slf4j
@Configuration
public class MetricsConfig {

    private final MeterRegistry meterRegistry;

    @Getter
    private final Timer retrieveTimer;
    @Getter
    private final Counter cacheHits;
    @Getter
    private final Counter cacheMisses;
    @Getter
    private final Counter cragTriggered;
    @Getter
    private final Counter agentToolCalls;

    public MetricsConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        this.retrieveTimer = Timer.builder("devknow.rag.retrieve.timing")
                .description("RAG 检索耗时")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        this.cacheHits = Counter.builder("devknow.cache.hits")
                .description("语义缓存命中次数")
                .register(meterRegistry);

        this.cacheMisses = Counter.builder("devknow.cache.misses")
                .description("语义缓存未命中次数")
                .register(meterRegistry);

        this.cragTriggered = Counter.builder("devknow.rag.crag.triggered")
                .description("CRAG 纠错触发次数")
                .register(meterRegistry);

        this.agentToolCalls = Counter.builder("devknow.agent.tool.calls")
                .description("Agent 工具调用次数")
                .register(meterRegistry);
    }

    @PostConstruct
    public void init() {
        log.info("MetricsConfig 初始化完成：已注册 {} 个自定义指标",
                meterRegistry.getMeters().stream()
                        .filter(m -> m.getId().getName().startsWith("devknow"))
                        .count());
    }

    /**
     * 增加 Token 消耗计数。
     * 由 TokenAuditService 在每次 LLM 调用时调用。
     */
    public void recordToken(String type, int inputTokens, int outputTokens) {
        Counter.builder("devknow.token.input")
                .tag("type", type)
                .description("输入 Token 数")
                .register(meterRegistry)
                .increment(inputTokens);

        Counter.builder("devknow.token.output")
                .tag("type", type)
                .description("输出 Token 数")
                .register(meterRegistry)
                .increment(outputTokens);
    }
}
