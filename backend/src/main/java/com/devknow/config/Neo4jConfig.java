package com.devknow.config;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Neo4j Embedded 数据库配置。
 *
 * <p>Neo4j 以嵌入模式运行在 JVM 进程内，不依赖独立容器。
 * 数据库文件存储在 data/neo4j/ 目录下，随应用启停。
 */
@Slf4j
@Configuration
public class Neo4jConfig {

    @Value("${app.neo4j.data-dir:data/neo4j}")
    private String dataDir;

    @Bean(destroyMethod = "shutdown")
    public DatabaseManagementService neo4jManagementService() throws IOException {
        Path storeDir = Path.of(dataDir);
        Files.createDirectories(storeDir);

        log.info("Neo4j Embedded 初始化: dir={}", storeDir.toAbsolutePath());

        DatabaseManagementService managementService = new DatabaseManagementServiceBuilder(storeDir)
                .build();

        log.info("Neo4j Embedded 已启动");
        return managementService;
    }
}
