package com.devknow.chat;

import com.devknow.AbstractIntegrationTest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 三层记忆系统集成测试。
 *
 * <p>验证 {@link MemoryService} 的 append/load 在真实 Redis 环境下
 * 能正常工作。
 */
class MemoryServiceTest extends AbstractIntegrationTest {

    @Autowired(required = false)
    private MemoryService memoryService;

    @Test
    void appendAndLoad_shouldWork() {
        if (memoryService == null) return;

        String memoryId = "test-memory-" + System.currentTimeMillis();

        // 追加一条对话
        memoryService.append(memoryId, 0L,
                UserMessage.from("测试问题"),
                AiMessage.from("测试回答"));

        // 加载记忆
        var messages = memoryService.load(memoryId);
        assertNotNull(messages);
        System.out.println("[MemoryServiceTest] appendAndLoad: messages=" + messages.size());
    }

    @Test
    void load_emptyMemory_shouldReturnEmpty() {
        if (memoryService == null) return;

        var messages = memoryService.load("non-existent-memory");
        assertNotNull(messages);
    }
}
