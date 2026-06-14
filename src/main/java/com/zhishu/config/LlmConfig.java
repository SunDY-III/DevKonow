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
import java.util.concurrent.Semaphore;

/**
 * LangChain4j 模型装配。
 * 国产模型（DeepSeek / 通义 / 智谱）都兼容 OpenAI 协议，换模型只改 base-url + model 配置。
 *
 * <p>api-key 允许留空：直连官方时用环境变量注入真实 key；接第三方中转 / 本地 one-api / Ollama
 * 等不校验 key 的网关时留空即可。留空时这里回填一个占位串，避免 OpenAI 客户端因 key 为 null/空
 * 在构造阶段抛错（占位串不会被不校验的网关使用）。</p>
 */
@Slf4j
@Configuration
public class LlmConfig {

    /** OpenAI 客户端要求 key 非空；网关不校验时用它占位 */
    private static final String PLACEHOLDER_KEY = "EMPTY";

    @Value("${llm.base-url}")           private String baseUrl;
    @Value("${llm.api-key}")            private String apiKey;
    @Value("${llm.chat-model}")         private String chatModel;
    @Value("${llm.embedding-base-url}") private String embBaseUrl;
    @Value("${llm.embedding-api-key}")  private String embApiKey;
    @Value("${llm.embedding-model}")    private String embModel;
    @Value("${llm.max-concurrency}")    private int maxConcurrency;

    /** key 留空时回填占位串，并打印提示，方便排查“为什么没带 key” */
    private String resolveKey(String raw, String which) {
        if (StringUtils.hasText(raw)) return raw.trim();
        log.warn("{} api-key 未配置，使用占位串 '{}'（仅适用于不校验 key 的第三方中转/本地网关；" +
                "直连官方请设置对应环境变量）", which, PLACEHOLDER_KEY);
        return PLACEHOLDER_KEY;
    }

    /** 非流式模型：工单分类、历史摘要压缩、Agent 推理用 */
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl).apiKey(resolveKey(apiKey, "chat")).modelName(chatModel)
                .temperature(0.2)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /** 流式模型：SSE 对话用 */
    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl).apiKey(resolveKey(apiKey, "chat")).modelName(chatModel)
                .temperature(0.3)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(embBaseUrl).apiKey(resolveKey(embApiKey, "embedding")).modelName(embModel)
                .timeout(Duration.ofSeconds(30))
                .build();
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
