package com.devknow.rag.router;

import com.devknow.auth.UserKnowledgeRole;
import com.devknow.config.rerank.LevelResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 动态路由单元测试。
 *
 * <p>验证 {@link DynamicRouter} 在 disabled 或 LLM 不可用时
 * 能正确降级返回默认参数。
 */
class DynamicRouterTest {

    @Test
    void disabled_shouldReturnDefault() {
        DynamicRouter router = new DynamicRouter(null, null) {
            @Override
            public DynamicRouteResult route(String question, LevelResult levelResult, UserKnowledgeRole role) {
                return new DynamicRouteResult();
            }
        };
        // 当 disabled 时返回默认值
        DynamicRouteResult result = router.route("test", new LevelResult(3, 0.8, ""), null);
        assertNotNull(result);
        assertEquals(500, result.getChunkSize());
        assertTrue(result.isHydeEnabled());
    }

    @Test
    void nullQuestion_shouldReturnDefault() {
        DynamicRouter router = new DynamicRouter(null, null) {
            @Override
            public DynamicRouteResult route(String question, LevelResult levelResult, UserKnowledgeRole role) {
                return new DynamicRouteResult();
            }
        };
        DynamicRouteResult result = router.route(null, null, null);
        assertNotNull(result);
    }
}
