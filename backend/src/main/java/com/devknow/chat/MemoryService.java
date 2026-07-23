package com.devknow.chat;

import com.devknow.chat.memory.FactExtractor;
import com.devknow.chat.memory.FactMemoryStore;
import com.devknow.chat.memory.MemoryFact;
import com.devknow.governance.TokenAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 三层记忆系统 —— 短期/中期/长期记忆。
 *
 * <p>替代旧的单层摘要压缩架构：
 * <ul>
 *   <li><b>短期记忆</b>：保留最近 N 轮完整对话原文（Redis，TTL 3 天）</li>
 *   <li><b>中期记忆</b>：历史对话的结构化摘要（JSON：主题/决策/未决问题），超出窗口时由 LLM 生成</li>
 *   <li><b>长期记忆</b>：原子性事实陈述（DECISION / PREFERENCE / REQUIREMENT 等），
 *       从被压缩的历史中提取，存入 Redis 持久化，支持修正</li>
 * </ul>
 */
@Slf4j
@Service
public class MemoryService {

    private final RedisChatMemoryStore memoryStore;
    private final TokenAuditService tokenAuditService;
    private final StringRedisTemplate redis;
    private final ChatLanguageModel chatModel;
    private final FactMemoryStore factMemoryStore;
    private final FactExtractor factExtractor;
    private final ObjectMapper objectMapper;

    /** 结构化摘要的 Redis key 前缀 */
    private static final String SUMMARY_KEY_PREFIX = "summary:";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    @Value("${app.memory.window-size}")       private int windowSize;
    @Value("${app.memory.summary-trigger}")   private int summaryTrigger;
    @Value("${app.memory.fact-extraction-enabled:true}")  private boolean factExtractionEnabled;

    public MemoryService(RedisChatMemoryStore memoryStore,
                         TokenAuditService tokenAuditService,
                         StringRedisTemplate redis,
                         @Qualifier("fastChatLanguageModel") ChatLanguageModel chatModel,
                         FactMemoryStore factMemoryStore,
                         FactExtractor factExtractor,
                         ObjectMapper objectMapper) {
        this.memoryStore = memoryStore;
        this.tokenAuditService = tokenAuditService;
        this.redis = redis;
        this.chatModel = chatModel;
        this.factMemoryStore = factMemoryStore;
        this.factExtractor = factExtractor;
        this.objectMapper = objectMapper;
    }

    // ==================== 短期记忆（原文消息） + 长期记忆（事实） ====================

    /**
     * 加载对话记忆：短期消息 + 中期摘要 + 长期事实。
     *
     * <p>返回的消息列表结构：
     * <ol>
     *   <li>[中期摘要] SystemMessage（如有）</li>
     *   <li>最近 N 轮对话原文（短期）</li>
     *   <li>[长期事实] SystemMessage 追加在后（如有）</li>
     * </ol>
     */
    public List<ChatMessage> load(String memoryId) {
        List<ChatMessage> messages = new ArrayList<>(memoryStore.getMessages(memoryId));

        // 追加长期事实：最近 10 条未过时的事实
        List<MemoryFact> activeFacts = factMemoryStore.getActiveFacts(memoryId, 10);
        if (!activeFacts.isEmpty()) {
            StringBuilder sb = new StringBuilder("[长期记忆]\n");
            for (MemoryFact f : activeFacts) {
                sb.append("• [").append(f.getCategory()).append("] ").append(f.getText()).append("\n");
            }
            messages.add(SystemMessage.from(sb.toString().strip()));
        }

        return messages;
    }

    // ==================== 写入 + 压缩 ====================

    /**
     * 追加一轮对话，触发压缩条件时执行：
     * 1. 短期 → 中期：结构化摘要
     * 2. 中期 → 长期：事实提取
     */
    public void append(String memoryId, Long userId, UserMessage userMessage, AiMessage aiMessage) {
        // Redis 互斥锁防止并发 append 覆盖写入
        String lockKey = "lock:memory:" + memoryId;
        String lockValue = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, lockValue, Duration.ofSeconds(30));
        if (!Boolean.TRUE.equals(acquired)) {
            log.warn("memory lock contention for {}, proceeding without lock to avoid blocking", memoryId);
        }
        try {
            List<ChatMessage> messages = load(memoryId);
            messages.add(userMessage);
            messages.add(aiMessage);

            if (messages.size() > summaryTrigger) {
                messages = compress(memoryId, userId, messages);
            }
            memoryStore.updateMessages(memoryId, messages);
        } finally {
            if (Boolean.TRUE.equals(acquired)) {
                redis.execute(UNLOCK_SCRIPT, List.of(lockKey), lockValue);
            }
        }
    }

    // ==================== 中期记忆：结构化摘要（替代旧 200 字摘要） ====================

    /**
     * 压缩历史对话：
     * 1. 提取早期对话 → 结构化摘要（JSON：主题/决策/未决问题）→ 存 Redis + 注入消息列表
     * 2. 提取早期对话 → 原子事实 → 存 FactMemoryStore
     * 3. 保留最近 keep 条原文
     */
    private List<ChatMessage> compress(String memoryId, Long userId, List<ChatMessage> messages) {
        int safeMax = ((summaryTrigger - 3) / 2) * 2;
        int keep = Math.min(windowSize * 2, safeMax);
        keep = Math.max(keep, 2);

        List<ChatMessage> old = messages.subList(0, messages.size() - keep);
        List<ChatMessage> recent = new ArrayList<>(messages.subList(messages.size() - keep, messages.size()));

        // 1. 生成结构化摘要（中期记忆）
        String structuredSummary = generateStructuredSummary(old, userId);
        if (structuredSummary != null) {
            saveStructuredSummary(memoryId, structuredSummary);
        }

        // 2. 提取长期事实（从即将丢弃的旧消息中）
        if (factExtractionEnabled && old.size() >= 4) {
            extractAndSaveFacts(old, memoryId, userId);
        }

        // 3. 构建压缩后的消息列表
        List<ChatMessage> compressed = new ArrayList<>();
        String summaryText = structuredSummary != null
                ? "[对话摘要] " + structuredSummary
                : "[对话摘要] 共 " + old.size() + " 条历史消息已归档";
        compressed.add(SystemMessage.from(summaryText));
        compressed.addAll(recent);

        log.info("memory compressed: {} → {} messages + facts, memoryId={}",
                messages.size(), compressed.size(), memoryId);
        return compressed;
    }

    /**
     * 生成结构化摘要 — 输出 JSON 含 topics / decisions / openQuestions。
     * 比旧版 200 字摘要信息密度更高、结构更清晰。
     */
    private String generateStructuredSummary(List<ChatMessage> oldMessages, Long userId) {
        String conversationText = oldMessages.stream()
                .filter(m -> !(m instanceof SystemMessage))
                .map(m -> {
                    if (m instanceof UserMessage) return "用户: " + m.text();
                    if (m instanceof AiMessage) return "助手: " + m.text();
                    return m.type() + ": " + m.text();
                })
                .collect(Collectors.joining("\n"));

        if (conversationText.length() > 5000) {
            conversationText = conversationText.substring(0, 5000) + "\n...（截断）";
        }

        String prompt = """
                你是一个对话摘要助手。为以下对话生成结构化摘要。

                格式要求：
                - topics: 讨论过的主题列表
                - decisions: 已作出的决定列表
                - openQuestions: 未解决的问题列表
                - keyPoints: 关键结论列表

                对话：
                %s

                返回 JSON 格式：
                {"topics": ["...", "..."], "decisions": ["...", "..."], "openQuestions": ["...", "..."], "keyPoints": ["...", "..."]}
                如果某类为空，返回空数组。只返回 JSON。
                """.formatted(conversationText);

        try {
            Response<AiMessage> resp = chatModel.generate(UserMessage.from(prompt));
            if (resp.tokenUsage() != null) {
                tokenAuditService.record(userId, "SUMMARY",
                        resp.tokenUsage().inputTokenCount(), resp.tokenUsage().outputTokenCount());
            }

            String json = resp.content().text();
            // 验证 JSON 合法性
            var parsed = objectMapper.readValue(json, Map.class);
            if (parsed.containsKey("topics") || parsed.containsKey("decisions")) {
                return json;
            }
            return null;
        } catch (Exception e) {
            log.warn("Structured summary generation failed, fallback to text summary", e);
            return generateFallbackSummary(conversationText, userId);
        }
    }

    /** 降级：旧版 200 字文本摘要 */
    private String generateFallbackSummary(String conversationText, Long userId) {
        String prompt = "请把以下对话压缩成不超过 200 字的客观摘要，保留关键事实与未决问题：\n" + conversationText;
        try {
            Response<AiMessage> resp = chatModel.generate(UserMessage.from(prompt));
            if (resp.tokenUsage() != null) {
                tokenAuditService.record(userId, "SUMMARY",
                        resp.tokenUsage().inputTokenCount(), resp.tokenUsage().outputTokenCount());
            }
            return resp.content().text();
        } catch (Exception e) {
            log.warn("Fallback summary also failed", e);
            return null;
        }
    }

    // ==================== 结构化摘要持久化 ====================

    private void saveStructuredSummary(String memoryId, String summaryJson) {
        try {
            redis.opsForValue().set(
                    SUMMARY_KEY_PREFIX + memoryId, summaryJson, Duration.ofDays(7));
        } catch (Exception e) {
            log.warn("Failed to save structured summary: memoryId={}", memoryId, e);
        }
    }

    /**
     * 获取结构化摘要（供外部使用，如调试界面）。
     */
    public String getStructuredSummary(String memoryId) {
        return redis.opsForValue().get(SUMMARY_KEY_PREFIX + memoryId);
    }

    // ==================== 长期记忆：事实提取 ====================

    private void extractAndSaveFacts(List<ChatMessage> oldMessages, String memoryId, Long userId) {
        try {
            List<MemoryFact> facts = factExtractor.extract(oldMessages, memoryId);
            if (!facts.isEmpty()) {
                factMemoryStore.saveAll(memoryId, facts);
                log.info("长期事实提取: {} facts from {} messages, memoryId={}",
                        facts.size(), oldMessages.size(), memoryId);
            }
        } catch (Exception e) {
            log.warn("Fact extraction failed (不影响主流程): memoryId={}", memoryId, e);
        }
    }
}
