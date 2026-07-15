package com.devknow.vector;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Milvus 客户端管理器。
 * 封装连接创建逻辑，连接失败时返回 null 不影响应用启动。
 * VectorStoreService 每次操作前检查客户端是否可用。
 */
@Slf4j
@Component
public class MilvusClientManager {

    private final String host;
    private final int port;
    private volatile MilvusClientV2 client;

    public MilvusClientManager(
            @Value("${milvus.host:localhost}") String host,
            @Value("${milvus.port:19530}") int port) {
        this.host = host;
        this.port = port;
    }

    public MilvusClientV2 getClient() {
        if (client != null) return client;
        synchronized (this) {
            if (client != null) return client;
            try {
                ConnectConfig config = ConnectConfig.builder()
                        .uri("http://" + host + ":" + port)
                        .connectTimeoutMs(3000)
                        .build();
                client = new MilvusClientV2(config);
                log.info("Milvus 客户端已连接: {}:{}", host, port);
            } catch (Exception e) {
                log.warn("Milvus 连接失败，向量搜索不可用: {}", e.getMessage());
            }
            return client;
        }
    }

    public boolean isAvailable() {
        return getClient() != null;
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            try { client.close(10); } catch (Exception ignored) {}
        }
    }
}
