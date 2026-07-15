package com.devknow.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Qdrant 向量数据库配置。
 * 管理连接生命周期 + 集合初始化（自动创建集合）。
 *
 * <p>替代原有的 MilvusConfig，Qdrant 更轻量、无需复杂索引配置，
 * 默认使用余弦相似度 + HNSW 索引。
 */
@Slf4j
@Configuration
public class QdrantConfig {

    @Value("${qdrant.host:localhost}")       private String host;
    @Value("${qdrant.port:6334}")            private int port;
    @Value("${qdrant.collection:devknow_vectors}") private String collectionName;
    @Value("${qdrant.dimension:1536}")       private int dimension;

    @Bean
    public String qdrantCollectionName() {
        return collectionName;
    }

    /**
     * 启动时自动初始化集合。
     * Qdrant 无需预定义 schema（schemaless），只需创建集合并指定向量维度/距离度量。
     */
    @PostConstruct
    public void initCollection() {
        QdrantClient client = null;
        try {
            client = new QdrantClient(
                    QdrantGrpcClient.newBuilder(host, port, false).build());

            var existing = client.listCollectionsAsync().get(10, TimeUnit.SECONDS);
            boolean exists = existing.stream()
                    .anyMatch(name -> collectionName.equals(name));

            if (!exists) {
                log.info("Qdrant 集合不存在，创建: {} (dim={})", collectionName, dimension);
                client.createCollectionAsync(collectionName,
                        VectorParams.newBuilder()
                                .setDistance(Distance.Cosine)
                                .setSize(dimension)
                                .build())
                        .get(30, TimeUnit.SECONDS);
                log.info("Qdrant 集合已创建，distance=COSINE, size={}", dimension);
            } else {
                log.info("Qdrant 集合已存在: {}", collectionName);
            }

        } catch (Exception e) {
            log.warn("Qdrant 初始化失败（Qdrant 未就绪时可忽略）: {}", e.getMessage());
        } finally {
            if (client != null) {
                try { client.close(); } catch (Exception ignored) {}
            }
        }
    }
}
