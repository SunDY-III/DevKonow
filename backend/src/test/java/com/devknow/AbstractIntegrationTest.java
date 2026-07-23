package com.devknow;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试基类 —— 自动启动 MySQL + Redis 容器。
 *
 * <p>测试类继承此类即可获得真实中间件环境。
 * 容器在测试类首次使用时启动，全部测试完成后自动销毁。
 *
 * <p>使用方式：
 * <pre>{@code
 * class MyTest extends AbstractIntegrationTest {
 *     @Test void testSomething() { ... }
 * }
 * }</pre>
 *
 * <p>注意：Qdrant 和 Neo4j 容器较重，仅在需要时由具体测试类按需添加。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractIntegrationTest {

    // ==================== MySQL ====================

    private static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("devknow_test")
            .withUsername("test")
            .withPassword("test");

    // ==================== Redis ====================

    private static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server --requirepass testpass");

    // ==================== 动态属性注入 ====================

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // MySQL
        mysql.start();
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);

        // Redis
        redis.start();
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "testpass");

        // 默认关闭外部依赖（Qdrant、RabbitMQ、LLM 调用等）
        registry.add("qdrant.host", () -> "localhost");
        registry.add("qdrant.port", () -> "0");
        registry.add("llm.api-key", () -> "test-key");
        registry.add("llm.chat-model", () -> "mock-model");
    }
}
