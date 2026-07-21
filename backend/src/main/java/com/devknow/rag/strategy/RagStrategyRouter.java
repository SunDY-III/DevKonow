package com.devknow.rag.strategy;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RAG 策略路由器 —— 根据场景名称路由到对应的 {@link ChunkStrategy}。
 *
 * <p>在应用启动时从 {@code application.yml} 加载各场景的配置参数，
 * 构建为一组只读的 ChunkStrategy 快照，运行时通过 {@link #resolve(String)}
 * 获取。
 *
 * <p>若请求的场景不存在，回退到 {@code "default"} 策略。
 *
 * <p>使用示例：
 * <pre>{@code
 * ChunkStrategy strategy = strategyRouter.resolve("learn");
 * textSplitter.splitWithParams(content, strategy.getChunkSize(), strategy.getChunkOverlap());
 * }</pre>
 */
@Slf4j
@Component
public class RagStrategyRouter {

    private final Map<String, ChunkStrategy> strategyMap = new HashMap<>();

    // ======================== 公共参数（各场景共用） ========================

    @Value("${app.rag.rerank-top-n:4}")
    private int rerankTopN;

    @Value("${app.rag.vector-top-k:8}")
    private int vectorTopK;

    @Value("${app.rag.keyword-top-k:8}")
    private int keywordTopK;

    // ======================== learn（学习研读） ========================

    @Value("${app.rag.strategies.learn.chunk-size:1024}")
    private int learnChunkSize;
    @Value("${app.rag.strategies.learn.chunk-overlap:128}")
    private int learnChunkOverlap;
    @Value("${app.rag.strategies.learn.skip-checkpoints:1,2}")
    private String learnSkipCheckpoints;
    @Value("${app.rag.strategies.learn.hyde-enabled:false}")
    private boolean learnHydeEnabled;
    @Value("${app.rag.strategies.learn.mmr-enabled:false}")
    private boolean learnMmrEnabled;

    // ======================== interview（严格代码面试/审查） ========================

    @Value("${app.rag.strategies.interview.chunk-size:2048}")
    private int interviewChunkSize;
    @Value("${app.rag.strategies.interview.chunk-overlap:320}")
    private int interviewChunkOverlap;
    @Value("${app.rag.strategies.interview.skip-checkpoints:}")
    private String interviewSkipCheckpoints;
    @Value("${app.rag.strategies.interview.hyde-enabled:true}")
    private boolean interviewHydeEnabled;
    @Value("${app.rag.strategies.interview.mmr-enabled:true}")
    private boolean interviewMmrEnabled;

    // ======================== safety（安全审查） ========================

    @Value("${app.rag.strategies.safety.chunk-size:256}")
    private int safetyChunkSize;
    @Value("${app.rag.strategies.safety.chunk-overlap:64}")
    private int safetyChunkOverlap;
    @Value("${app.rag.strategies.safety.skip-checkpoints:}")
    private String safetySkipCheckpoints;
    @Value("${app.rag.strategies.safety.hyde-enabled:true}")
    private boolean safetyHydeEnabled;
    @Value("${app.rag.strategies.safety.mmr-enabled:true}")
    private boolean safetyMmrEnabled;

    // ======================== code（代码检索） ========================

    @Value("${app.rag.strategies.code.chunk-size:384}")
    private int codeChunkSize;
    @Value("${app.rag.strategies.code.chunk-overlap:0}")
    private int codeChunkOverlap;
    @Value("${app.rag.strategies.code.skip-checkpoints:}")
    private String codeSkipCheckpoints;
    @Value("${app.rag.strategies.code.hyde-enabled:true}")
    private boolean codeHydeEnabled;
    @Value("${app.rag.strategies.code.mmr-enabled:false}")
    private boolean codeMmrEnabled;

    // ======================== doc（中文技术文档） ========================

    @Value("${app.rag.strategies.doc.chunk-size:500}")
    private int docChunkSize;
    @Value("${app.rag.strategies.doc.chunk-overlap:80}")
    private int docChunkOverlap;
    @Value("${app.rag.strategies.doc.skip-checkpoints:}")
    private String docSkipCheckpoints;
    @Value("${app.rag.strategies.doc.hyde-enabled:true}")
    private boolean docHydeEnabled;
    @Value("${app.rag.strategies.doc.mmr-enabled:true}")
    private boolean docMmrEnabled;

    // ======================== default（回退兜底） ========================

    @Value("${app.rag.strategies.default.chunk-size:500}")
    private int defaultChunkSize;
    @Value("${app.rag.strategies.default.chunk-overlap:80}")
    private int defaultChunkOverlap;
    @Value("${app.rag.strategies.default.skip-checkpoints:}")
    private String defaultSkipCheckpoints;
    @Value("${app.rag.strategies.default.hyde-enabled:true}")
    private boolean defaultHydeEnabled;
    @Value("${app.rag.strategies.default.mmr-enabled:true}")
    private boolean defaultMmrEnabled;

    // ======================== 初始化 ========================

    @PostConstruct
    public void init() {
        register("learn", learnChunkSize, learnChunkOverlap, learnSkipCheckpoints,
                learnHydeEnabled, learnMmrEnabled, 6, 10, 2);
        register("interview", interviewChunkSize, interviewChunkOverlap, interviewSkipCheckpoints,
                interviewHydeEnabled, interviewMmrEnabled, 4, 4, 1);
        register("safety", safetyChunkSize, safetyChunkOverlap, safetySkipCheckpoints,
                safetyHydeEnabled, safetyMmrEnabled, 4, 4, 1);
        register("code", codeChunkSize, codeChunkOverlap, codeSkipCheckpoints,
                codeHydeEnabled, codeMmrEnabled, 4, 4, 1);
        register("doc", docChunkSize, docChunkOverlap, docSkipCheckpoints,
                docHydeEnabled, docMmrEnabled, 4, 4, 1);
        register("default", defaultChunkSize, defaultChunkOverlap, defaultSkipCheckpoints,
                defaultHydeEnabled, defaultMmrEnabled, 4, 4, 1);

        log.info("RagStrategyRouter 初始化完成，已注册场景: {}", strategyMap.keySet());
        if (log.isDebugEnabled()) {
            strategyMap.forEach((name, s) -> log.debug(
                    "  场景 [{}]: chunkSize={}, overlap={}, skipCheckpoints={}, hyde={}, mmr={}",
                    name, s.getChunkSize(), s.getChunkOverlap(),
                    s.getSkipCheckpoints(), s.isHydeEnabled(), s.isMmrEnabled()));
        }
    }

    private void register(String scenario, int chunkSize, int chunkOverlap,
                          String skipCheckpointsStr, boolean hydeEnabled, boolean mmrEnabled,
                          int minFinalChunks, int vectorTopKOverride, int poolMultiplier) {
        List<Integer> skipList = parseSkipCheckpoints(skipCheckpointsStr);
        ChunkStrategy strategy = ChunkStrategy.builder()
                .scenario(scenario)
                .chunkSize(chunkSize)
                .chunkOverlap(chunkOverlap)
                .skipCheckpoints(skipList)
                .hydeEnabled(hydeEnabled)
                .mmrEnabled(mmrEnabled)
                .rerankTopN(this.rerankTopN)
                .vectorTopK(vectorTopKOverride > 0 ? vectorTopKOverride : this.vectorTopK)
                .keywordTopK(this.keywordTopK)
                .crossEncoderWeight("learn".equals(scenario) ? 0.5 : 0.7)
                .minFinalChunks(minFinalChunks)
                .candidatePoolMultiplier(poolMultiplier)
                .build();
        strategyMap.put(scenario, strategy);
    }

    /**
     * 根据场景名称获取策略。
     *
     * @param scenario 场景名称 (learn / interview / safety / code / doc / default)
     * @return 匹配的 ChunkStrategy，不存在时返回 default 策略
     */
    public ChunkStrategy resolve(String scenario) {
        if (scenario == null || !strategyMap.containsKey(scenario)) {
            return strategyMap.get("default");
        }
        return strategyMap.get(scenario);
    }

    /**
     * 获取所有已注册的场景名称集合（不含 "default"）。
     */
    public Set<String> getAvailableScenarios() {
        return strategyMap.keySet().stream()
                .filter(s -> !"default".equals(s))
                .collect(Collectors.toSet());
    }

    // ======================== 内部工具 ========================

    private List<Integer> parseSkipCheckpoints(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .toList();
    }
}
