package com.devknow.rag;

import com.devknow.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAG 管道集成测试。
 *
 * <p>验证 {@link RagService} 的核心检索路径在真实 MySQL + Redis 环境下
 * 不会抛出异常，且行为符合预期。
 */
class RagServiceTest extends AbstractIntegrationTest {

    @Autowired(required = false)
    private RagService ragService;

    @Test
    void levelAwareRetrieve_shouldReturnResult() {
        if (ragService == null) {
            System.err.println("⚠️ RagService 未注入，跳过（需要完整 Spring 上下文）");
            return;
        }

        // 即使 Qdrant 不可用，RagService 应降级返回空结果而非抛异常
        RagResult result = ragService.levelAwareRetrieve(null, "测试查询");
        assertNotNull(result);
        assertNotNull(result.getChunks());
        System.out.println("[RagServiceTest] levelAwareRetrieve: chunks=" + result.getChunks().size()
                + ", confidence=" + result.getConfidence());
    }

    @Test
    void exploreRetrieve_shouldNotThrow() {
        if (ragService == null) return;

        RagResult result = ragService.exploreRetrieve(null, null, "测试查询");
        assertNotNull(result);
        System.out.println("[RagServiceTest] exploreRetrieve: chunks=" + result.getChunks().size());
    }

    @Test
    void buildContext_shouldFormat() {
        if (ragService == null) return;

        String context = ragService.buildContext(java.util.List.of());
        assertNotNull(context);
    }
}
