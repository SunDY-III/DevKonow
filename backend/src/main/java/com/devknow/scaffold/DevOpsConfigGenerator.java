package com.devknow.scaffold;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * DevOps 配置文件生成器。
 *
 * <p>使用模板库生成 Dockerfile / docker-compose / CI 配置。
 * 与 ScaffoldGenerator 共享模板管理逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevOpsConfigGenerator {

    private final ScaffoldGenerator scaffoldGenerator;

    /**
     * 生成 DevOps 配置文件。
     *
     * @param projectName  项目名称
     * @param techStack    技术栈 (spring-boot / go / nestjs 等)
     * @param includeCi    是否包含 CI 配置
     * @param outputDir    输出目录
     * @return 生成的文件列表
     */
    public List<String> generateDevOpsConfigs(String projectName, String techStack,
                                               boolean includeCi, String outputDir) {
        Map<String, String> variables = new HashMap<>();
        variables.put("projectName", projectName);
        variables.put("techStack", techStack);
        variables.put("javaVersion", "21");
        variables.put("nodeVersion", "20");

        List<String> generated = new ArrayList<>();

        // 生成 Dockerfile
        generated.addAll(generateDockerfile(techStack, variables, outputDir));

        // 生成 docker-compose.yml
        generated.addAll(generateDockerCompose(techStack, variables, outputDir));

        // 可选 CI 配置
        if (includeCi) {
            generated.addAll(generateCiConfig(techStack, variables, outputDir));
        }

        log.info("DevOps 配置生成完成，共 {} 个文件", generated.size());
        return generated;
    }

    private List<String> generateDockerfile(String techStack, Map<String, String> variables, String outputDir) {
        List<String> files = new ArrayList<>();
        Path outPath = Paths.get(outputDir);

        String dockerContent = switch (techStack.toLowerCase()) {
            case "spring-boot" -> """
                FROM eclipse-temurin:{{javaVersion}}-jre-alpine
                WORKDIR /app
                COPY target/*.jar app.jar
                EXPOSE 8080
                ENTRYPOINT ["java", "-jar", "app.jar"]
                """;
            case "go" -> """
                FROM golang:{{nodeVersion}}-alpine AS builder
                WORKDIR /src
                COPY go.mod go.sum ./
                RUN go mod download
                COPY . .
                RUN go build -o /app/server .

                FROM alpine:3.19
                WORKDIR /app
                COPY --from=builder /app/server .
                EXPOSE 8080
                ENTRYPOINT ["./server"]
                """;
            case "nestjs" -> """
                FROM node:{{nodeVersion}}-alpine AS builder
                WORKDIR /app
                COPY package*.json ./
                RUN npm ci
                COPY . .
                RUN npm run build

                FROM node:{{nodeVersion}}-alpine
                WORKDIR /app
                COPY --from=builder /app/dist ./dist
                COPY --from=builder /app/node_modules ./node_modules
                EXPOSE 3000
                ENTRYPOINT ["node", "dist/main"]
                """;
            default -> throw new IllegalArgumentException("不支持的技术栈: " + techStack);
        };

        try {
            Files.createDirectories(outPath);
            String resolved = scaffoldGenerator.getClass()
                    .getDeclaredMethod("replaceVariables", String.class, Map.class)
                    .invoke(scaffoldGenerator, dockerContent, variables)
                    .toString();
            Path dockerPath = outPath.resolve("Dockerfile");
            Files.writeString(dockerPath, resolved);
            files.add("Dockerfile");
            log.debug("生成 Dockerfile: {}", dockerPath);
        } catch (Exception e) {
            log.error("生成 Dockerfile 失败", e);
        }

        return files;
    }

    private List<String> generateDockerCompose(String techStack, Map<String, String> variables, String outputDir) {
        List<String> files = new ArrayList<>();
        Path outPath = Paths.get(outputDir);

        String composeContent = """
            version: '3.8'
            services:
              app:
                build: .
                ports:
                  - "8080:8080"
                environment:
                  - SPRING_PROFILES_ACTIVE=prod
                volumes:
                  - ./data:/app/data
            """;

        try {
            Files.createDirectories(outPath);
            String resolved = scaffoldGenerator.getClass()
                    .getDeclaredMethod("replaceVariables", String.class, Map.class)
                    .invoke(scaffoldGenerator, composeContent, variables)
                    .toString();
            Path composePath = outPath.resolve("docker-compose.yml");
            Files.writeString(composePath, resolved);
            files.add("docker-compose.yml");
            log.debug("生成 docker-compose.yml: {}", composePath);
        } catch (Exception e) {
            log.error("生成 docker-compose.yml 失败", e);
        }

        return files;
    }

    private List<String> generateCiConfig(String techStack, Map<String, String> variables, String outputDir) {
        List<String> files = new ArrayList<>();
        Path outPath = Paths.get(outputDir, ".github", "workflows");

        String ciContent = """
            name: CI
            on:
              push:
                branches: [main]
              pull_request:
                branches: [main]
            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4
                  - name: Set up JDK {{javaVersion}}
                    uses: actions/setup-java@v4
                    with:
                      java-version: '{{javaVersion}}'
                      distribution: 'temurin'
                  - name: Build with Maven
                    run: ./mvnw clean package
            """;

        try {
            Files.createDirectories(outPath);
            String resolved = scaffoldGenerator.getClass()
                    .getDeclaredMethod("replaceVariables", String.class, Map.class)
                    .invoke(scaffoldGenerator, ciContent, variables)
                    .toString();
            Path ciPath = outPath.resolve("ci.yml");
            Files.writeString(ciPath, resolved);
            files.add(".github/workflows/ci.yml");
            log.debug("生成 CI 配置: {}", ciPath);
        } catch (Exception e) {
            log.error("生成 CI 配置失败", e);
        }

        return files;
    }
}
