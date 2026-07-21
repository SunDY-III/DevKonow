package com.devknow.scaffold;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 模板管理 API 服务层。
 *
 * <p>模板列表 / 模板详情 / 生成（SSE 进度推送）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    private final ScaffoldGenerator scaffoldGenerator;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    /**
     * 获取模板列表。
     */
    public List<TemplateMetadata> listTemplates() {
        List<TemplateMetadata> list = new ArrayList<>();

        TemplateMetadata t1 = new TemplateMetadata();
        t1.setId("spring-boot-3-mysql");
        t1.setName("Spring Boot 3 + MySQL");
        t1.setDescription("基于 Spring Boot 3 和 MySQL 的 RESTful API 项目模板");
        t1.setTechStack(List.of("Spring Boot 3", "MySQL", "Maven", "Java 21"));
        t1.setTags(List.of("java", "spring", "rest", "jpa"));
        t1.setVariables(createProjectVariables());
        list.add(t1);

        TemplateMetadata t2 = new TemplateMetadata();
        t2.setId("go-gin-redis");
        t2.setName("Go + Gin + Redis");
        t2.setDescription("基于 Go Gin 框架和 Redis 的高性能 API 项目模板");
        t2.setTechStack(List.of("Go", "Gin", "Redis", "Docker"));
        t2.setTags(List.of("go", "gin", "api", "redis"));
        t2.setVariables(createProjectVariables());
        list.add(t2);

        TemplateMetadata t3 = new TemplateMetadata();
        t3.setId("nestjs-mongo");
        t3.setName("NestJS + MongoDB");
        t3.setDescription("基于 NestJS 和 MongoDB 的企业级 Node.js 后端模板");
        t3.setTechStack(List.of("NestJS", "MongoDB", "TypeScript", "Node 20"));
        t3.setTags(List.of("node", "nestjs", "typescript", "mongodb"));
        t3.setVariables(createProjectVariables());
        list.add(t3);

        return list;
    }

    /**
     * 获取模板详情。
     */
    public TemplateMetadata getTemplate(String id) {
        return listTemplates().stream()
                .filter(t -> t.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("模板不存在: " + id));
    }

    /**
     * 生成脚手架，通过 SSE 推送进度。
     */
    public SseEmitter generate(String templateId, Map<String, String> variables, String projectName) {
        SseEmitter emitter = new SseEmitter(300_000L);

        executor.execute(() -> {
            try {
                sendProgress(emitter, "init", "准备模板...", 0);
                Thread.sleep(300);

                sendProgress(emitter, "unzip", "解压模板文件中...", 20);
                Thread.sleep(500);

                sendProgress(emitter, "variables", "执行变量替换...", 40);
                Thread.sleep(300);

                String outputDir = "generated/" + projectName;
                List<String> files = scaffoldGenerator.generate(templateId, variables, outputDir);

                sendProgress(emitter, "writing", "写入项目文件...", 70);
                Thread.sleep(300);

                sendProgress(emitter, "complete", "项目生成完成！", 100);
                emitter.send(SseEmitter.event()
                        .name("done")
                        .data(Map.of("projectName", projectName, "files", files, "outputDir", outputDir)));
                emitter.complete();

            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(Map.of("message", e.getMessage())));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private void sendProgress(SseEmitter emitter, String stage, String message, int percent) throws IOException {
        emitter.send(SseEmitter.event()
                .name("phase")
                .data(Map.of("stage", stage, "message", message, "percent", percent)));
    }

    private List<TemplateMetadata.TemplateVariable> createProjectVariables() {
        List<TemplateMetadata.TemplateVariable> vars = new ArrayList<>();
        TemplateMetadata.TemplateVariable v1 = new TemplateMetadata.TemplateVariable();
        v1.setKey("projectName");
        v1.setLabel("项目名称");
        v1.setDefaultValue("my-project");
        v1.setDescription("Maven/Gradle 项目名称");
        v1.setRequired(true);
        vars.add(v1);

        TemplateMetadata.TemplateVariable v2 = new TemplateMetadata.TemplateVariable();
        v2.setKey("basePackage");
        v2.setLabel("基础包名");
        v2.setDefaultValue("com.example");
        v2.setDescription("Java 基础包名，如 com.example.myapp");
        v2.setRequired(true);
        vars.add(v2);

        TemplateMetadata.TemplateVariable v3 = new TemplateMetadata.TemplateVariable();
        v3.setKey("dbPassword");
        v3.setLabel("数据库密码");
        v3.setDefaultValue("changeit");
        v3.setDescription("MySQL/PostgreSQL 数据库密码");
        v3.setRequired(false);
        vars.add(v3);

        return vars;
    }
}
