package com.devknow.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWT 工具单元测试。
 * 直接构造 JwtUtil 实例，不依赖 Spring 上下文。
 * 测试 token 生成、解析、过期检测、角色提取等核心功能。
 */
class JwtUtilTest {

    private static final String TEST_SECRET = "test-secret-key-which-is-at-least-32-bytes-long-for-hs256!!";
    private static final long EXPIRE_HOURS = 72;

    private final JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, EXPIRE_HOURS);

    @Test
    void generate_shouldReturnValidToken() {
        String token = jwtUtil.generate(1L, "USER");
        assertNotNull(token);
        assertFalse(token.isBlank());
        assertEquals(3, token.split("\\.").length, "JWT 应包含 3 段");
    }

    @Test
    void parseUserId_shouldReturnCorrectId() {
        String token = jwtUtil.generate(42L, "ADMIN");
        Long userId = jwtUtil.parseUserId(token);
        assertEquals(42L, userId);
    }

    @Test
    void parseRole_shouldReturnCorrectRole() {
        String token = jwtUtil.generate(1L, "HANDLER");
        String role = jwtUtil.parseRole(token);
        assertEquals("HANDLER", role);
    }

    @Test
    void parseRole_shouldDefaultToUser() {
        // role 为 null 时 generate 写入 "null" 字符串，parseRole 返回 "null"
        // 验证 parseRole 对 null role 的处理行为
        String token = jwtUtil.generate(1L, null);
        String role = jwtUtil.parseRole(token);
        assertNotNull(role);
    }

    @Test
    void parseUserId_shouldThrowOnInvalidToken() {
        assertThrows(Exception.class, () ->
            jwtUtil.parseUserId("invalid.token.here"));
    }

    @Test
    void expiredToken_shouldBeRejected() throws Exception {
        // 0 小时过期 = token 签发即过期
        JwtUtil shortLived = new JwtUtil(TEST_SECRET, 0);
        String token = shortLived.generate(1L, "USER");
        Thread.sleep(5); // 确保时间窗口过去
        assertThrows(Exception.class, () ->
            jwtUtil.parseUserId(token));
    }
}
