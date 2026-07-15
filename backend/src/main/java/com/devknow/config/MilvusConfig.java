package com.devknow.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.List;

/**
 * Milvus 向量数据库配置。
 * 管理连接生命周期 + 集合初始化（自动创建集合和索引）。
 */
@Slf4j
@Configuration
public class MilvusConfig {

    @Value("${milvus.host:localhost}")       private String host;
    @Value("${milvus.port:19530}")           private int port;
    @Value("${milvus.collection:devknow_vectors}") private String collectionName;
    @Value("${milvus.dimension:1536}")       private int dimension;

    private MilvusClientV2 client;

    @Bean @Lazy
    public MilvusClientV2 milvusClient() {
        if (client != null) return client;
        ConnectConfig config = ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .connectTimeoutMs(5000)
                .build();

        client = new MilvusClientV2(config);
        log.info("Milvus 客户端已连接: {}:{}", host, port);
        return client;
    }

    @Bean
    public String milvusCollectionName() {
        return collectionName;
    }

    /**
     * 启动时自动初始化集合和索引。
     */
    @PostConstruct
    public void initCollection() {
        try {
            MilvusClientV2 c = milvusClient();

            boolean exists = c.hasCollection(HasCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());

            if (!exists) {
                log.info("Milvus 集合不存在，创建: {} (dim={})", collectionName, dimension);

                // 使用 createSchema() 创建集合 schema，然后逐个添加字段
                CreateCollectionReq.CollectionSchema schema = c.createSchema();
                schema.addField(AddFieldReq.builder().fieldName("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(true).build());
                schema.addField(AddFieldReq.builder().fieldName("project_id").dataType(DataType.Int64).build());
                schema.addField(AddFieldReq.builder().fieldName("source").dataType(DataType.VarChar).maxLength(16).build());
                schema.addField(AddFieldReq.builder().fieldName("doc_id").dataType(DataType.Int64).build());
                schema.addField(AddFieldReq.builder().fieldName("chunk_id").dataType(DataType.Int64).build());
                schema.addField(AddFieldReq.builder().fieldName("seq").dataType(DataType.Int32).build());
                schema.addField(AddFieldReq.builder().fieldName("file_name").dataType(DataType.VarChar).maxLength(512).build());
                schema.addField(AddFieldReq.builder().fieldName("content").dataType(DataType.VarChar).maxLength(65535).build());
                schema.addField(AddFieldReq.builder().fieldName("vector").dataType(DataType.FloatVector).dimension(dimension).build());

                CreateCollectionReq createReq = CreateCollectionReq.builder()
                        .collectionName(collectionName)
                        .collectionSchema(schema)
                        .build();

                c.createCollection(createReq);
                log.info("Milvus 集合已创建");

                // 创建 IVF_FLAT 索引
                IndexParam indexParam = IndexParam.builder()
                        .fieldName("vector")
                        .indexType(IndexParam.IndexType.IVF_FLAT)
                        .metricType(IndexParam.MetricType.COSINE)
                        .extraParams(java.util.Map.of("nlist", 128))
                        .build();

                c.createIndex(CreateIndexReq.builder()
                        .collectionName(collectionName)
                        .indexParams(List.of(indexParam))
                        .build());

                log.info("Milvus 索引已创建: IVF_FLAT, nlist=128, metric=COSINE");

                c.loadCollection(LoadCollectionReq.builder()
                        .collectionName(collectionName)
                        .build());

                log.info("Milvus 集合已加载");
            } else {
                log.info("Milvus 集合已存在: {}", collectionName);
                c.loadCollection(LoadCollectionReq.builder()
                        .collectionName(collectionName)
                        .build());
            }

        } catch (Exception e) {
            log.warn("Milvus 初始化失败（Milvus 未就绪时可忽略）: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            try {
                client.close(10);
                log.info("Milvus 客户端已关闭");
            } catch (Exception ignored) {}
        }
    }
}
