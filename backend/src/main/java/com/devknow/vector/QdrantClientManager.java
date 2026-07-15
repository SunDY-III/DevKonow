package com.devknow.vector;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Qdrant 客户端管理器。
 * 封装连接创建逻辑，连接失败时返回 null 不影响应用启动。
 * VectorStoreService 每次操作前检查客户端是否可用。
 *
 * <p>替代原有的 MilvusClientManager，Qdrant 使用 gRPC 协议（端口 6334）。
 */
@Slf4j
@Component
public class QdrantClientManager {

    private final String host;
    private final int port;
    private volatile QdrantClient client;

    public QdrantClientManager(
            @Value("${qdrant.host:localhost}") String host,
            @Value("${qdrant.port:6334}") int port) {
        this.host = host;
        this.port = port;
    }

    public QdrantClient getClient() {
        if (client != null) return client;
        synchronized (this) {
            if (client != null) return client;
            try {
                client = new QdrantClient(
                        QdrantGrpcClient.newBuilder(host, port, false).build());
                log.info("Qdrant 客户端已连接: {}:{}", host, port);
            } catch (Exception e) {
                log.warn("Qdrant 连接失败，向量搜索不可用: {}", e.getMessage());
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
            try { client.close(); } catch (Exception ignored) {}
        }
    }
}
