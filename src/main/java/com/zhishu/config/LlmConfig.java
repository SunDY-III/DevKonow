package com.zhishu.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * LangChain4j 模型装配。
 *
 * <p>统一走 OpenAI 协议客户端，因此可对接任意「OpenAI 协议兼容」的服务：
 * 第三方 GPT 中转（gpt-4o / gpt-4o-mini / gpt-3.5-turbo 等）、OpenAI 官方、
 * 以及国产模型（DeepSeek / 通义 / 智谱）。换模型只改配置，不动业务代码。</p>
 *
 * <h3>接第三方 GPT 中转（本次重点）</h3>
 * 多数第三方 GPT 中转站的接入方式与官方一致，区别只在 base-url、model 名以及 key：
 * <ul>
 *   <li>{@code llm.base-url}：中转站给出的 OpenAI 兼容地址，一般形如
 *       {@code https://xxx.com/v1}（务必带 {@code /v1} 后缀）。</li>
 *   <li>{@code llm.api-key}：中转站签发的 key（GPT 中转一般强制校验，必须填）。</li>
 *   <li>{@code llm.chat-model}：例如 {@code gpt-4o-mini}、{@code gpt-4o}、{@code gpt-3.5-turbo}。</li>
 *   <li>{@code llm.embedding-model}：例如 {@code text-embedding-3-small}。</li>
 * </ul>
 *
 * <p>少数中转站需要额外请求头（如 {@code X-Api-Channel: openai}）或 organization，
 * 已分别通过 {@code llm.custom-headers}、{@code llm.organization-id} 支持，按需配置即可。</p>
 *
 * <p>api-key 允许留空：仅用于本地 one-api / Ollama 等不校验 key 的网关；留空时回填占位串，
 * 避免 OpenAI 客户端因 key 为 null/空在构造阶段抛错。接 GPT 中转时请务必配置真实 key。</p>
 */
@Slf4j
@Configuration
public class LlmConfig {

    /** OpenAI 客户端要求 key 非空；网关不校验时用它占位 */
    private static final String PLACEHOLDER_KEY = "EMPTY";

    // ---- 对话 / Embedding 基础配置 ----
    @Value("${llm.base-url}")           private String baseUrl;
    @Value("${llm.api-key}")            private String apiKey;
    @Value("${llm.chat-model}")         private String chatModel;
    @Value("${llm.embedding-base-url}") private String embBaseUrl;
    @Value("${llm.embedding-api-key}")  private String embApiKey;
    @Value("${llm.embedding-model}")    private String embModel;
    @Value("${llm.max-concurrency}")    private int maxConcurrency;

    // ---- 第三方 GPT 中转常用的可选项（不需要就留默认空值） ----
    /** OpenAI organization（个别中转 / 官方多组织账号需要），留空则不携带 */
    @Value("${llm.organization-id:}")   private String organizationId;
    /** 额外请求头，格式：K1:V1;K2:V2（个别中转需要鉴别渠道头），留空则不携带 */
    @Value("${llm.custom-headers:}")    private String customHeadersRaw;
    /** text-embedding-3-* 支持自定义维度；<=0 表示用模型默认维度 */
    @Value("${llm.embedding-dimensions:0}") private int embDimensions;
    /** 调试开关：打印请求/响应到日志（排查中转连通性时很有用），默认关 */
    @Value("${llm.log-requests:false}")  private boolean logRequests;
    @Value("${llm.log-responses:false}") private boolean logResponses;
    /** 单次调用失败的自动重试次数（中转偶发抖动时有用） */
    @Value("${llm.max-retries:2}")       private int maxRetries;
    /** 对话温度（流式 / 非流式分开，便于分别调） */
    @Value("${llm.chat-temperature:0.2}")    private double chatTemperature;
    @Value("${llm.stream-temperature:0.3}")  private double streamTemperature;
    /** 超时（秒） */
    @Value("${llm.chat-timeout-seconds:60}")    private long chatTimeoutSeconds;
    @Value("${llm.stream-timeout-seconds:120}") private long streamTimeoutSeconds;
    @Value("${llm.embedding-timeout-seconds:30}") private long embTimeoutSeconds;

    /** key 留空时回填占位串，并打印提示，方便排查“为什么没带 key” */
    private String resolveKey(String raw, String which) {
        if (StringUtils.hasText(raw)) return raw.trim();
        log.warn("{} api-key 未配置，使用占位串 '{}'（仅适用于本地 one-api/Ollama 等不校验 key 的网关；" +
                "接第三方 GPT 中转或 OpenAI 官方请配置真实 key，否则会被网关拒绝）", which, PLACEHOLDER_KEY);
        return PLACEHOLDER_KEY;
    }

    /** 解析 "K1:V1;K2:V2" 形式的自定义请求头；空串返回空 Map */
    private Map<String, String> parseCustomHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!StringUtils.hasText(customHeadersRaw)) return headers;
        for (String pair : customHeadersRaw.split(";")) {
            if (!StringUtils.hasText(pair)) continue;
            int idx = pair.indexOf(':');
            if (idx <= 0) {
                log.warn("custom-headers 片段格式应为 'Key:Value'，已忽略：{}", pair);
                continue;
            }
            headers.put(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
        }
        if (!headers.isEmpty()) log.info("LLM 自定义请求头已加载：{}", headers.keySet());
        return headers;
    }

    /** 非流式模型：工单分类、历史摘要压缩、Agent 推理用 */
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        var builder = OpenAiChatModel.builder()
                .baseUrl(baseUrl).apiKey(resolveKey(apiKey, "chat")).modelName(chatModel)
                .temperature(chatTemperature)
                .timeout(Duration.ofSeconds(chatTimeoutSeconds))
                .maxRetries(maxRetries)
                .logRequests(logRequests).logResponses(logResponses);
        if (StringUtils.hasText(organizationId)) builder.organizationId(organizationId.trim());
        Map<String, String> headers = parseCustomHeaders();
        if (!headers.isEmpty()) builder.customHeaders(headers);
        log.info("Chat 模型装配完成：base-url={}, model={}", baseUrl, chatModel);
        return builder.build();
    }

    /** 流式模型：SSE 对话用 */
    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        var builder = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl).apiKey(resolveKey(apiKey, "chat")).modelName(chatModel)
                .temperature(streamTemperature)
                .timeout(Duration.ofSeconds(streamTimeoutSeconds))
                .logRequests(logRequests).logResponses(logResponses);
        if (StringUtils.hasText(organizationId)) builder.organizationId(organizationId.trim());
        Map<String, String> headers = parseCustomHeaders();
        if (!headers.isEmpty()) builder.customHeaders(headers);
        return builder.build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        var builder = OpenAiEmbeddingModel.builder()
                .baseUrl(embBaseUrl).apiKey(resolveKey(embApiKey, "embedding")).modelName(embModel)
                .timeout(Duration.ofSeconds(embTimeoutSeconds))
                .maxRetries(maxRetries)
                .logRequests(logRequests).logResponses(logResponses);
        // text-embedding-3-small / -large 支持自定义维度；留 <=0 用模型默认值
        if (embDimensions > 0) builder.dimensions(embDimensions);
        if (StringUtils.hasText(organizationId)) builder.organizationId(organizationId.trim());
        Map<String, String> headers = parseCustomHeaders();
        if (!headers.isEmpty()) builder.customHeaders(headers);
        log.info("Embedding 模型装配完成：base-url={}, model={}, dimensions={}",
                embBaseUrl, embModel, embDimensions > 0 ? embDimensions : "default");
        return builder.build();
    }

    /**
     * LLM 调用层信号量：控制同时在途的模型请求数，防止突发流量打爆 API 配额。
     * （网关限流挡“请求量”，信号量挡“并发量”，两层各管一段）
     */
    @Bean
    public Semaphore llmConcurrencyLimiter() {
        return new Semaphore(maxConcurrency);
    }
}
