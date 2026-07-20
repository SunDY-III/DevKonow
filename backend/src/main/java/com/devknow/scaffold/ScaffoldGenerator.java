package com.devknow.scaffold;

import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 脚手架生成器。
 *
 * <p>建立模板库体系：按 tech-stack 索引的预置模板仓库。
 * LLM 仅用于模板选择推荐（分类任务，用 fastModel）。
 * 模板基础上做变量替换的微调。
 * 支持解压 zip 模板并执行变量替换。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScaffoldGenerator {

    private final ChatLanguageModel chatModel;

    /**
     * 根据模板和变量生成项目脚手架。
     *
     * @param templateId  模板 ID
     * @param variables   变量映射
     * @param outputDir   输出目录
     * @return 生成的文件列表
     */
    public List<String> generate(String templateId, Map<String, String> variables, String outputDir) {
        // 1. 查找模板 zip 路径
        Path templateZip = findTemplate(templateId);
        if (!Files.exists(templateZip)) {
            throw new IllegalArgumentException("模板不存在: " + templateId);
        }

        // 2. 解压模板
        List<String> generatedFiles = new ArrayList<>();
        Path outPath = Paths.get(outputDir);

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(templateZip.toFile()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path targetPath = outPath.resolve(entry.getName()).normalize();
                if (!targetPath.startsWith(outPath)) {
                    throw new SecurityException("Zip slip 攻击检测: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    // 读取内容并执行变量替换
                    String content = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    content = replaceVariables(content, variables);
                    Files.writeString(targetPath, content, StandardCharsets.UTF_8);
                    generatedFiles.add(entry.getName());
                    log.debug("生成文件: {}", entry.getName());
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            throw new RuntimeException("模板解压失败: " + templateId, e);
        }

        log.info("模板 {} 生成完成，共 {} 个文件", templateId, generatedFiles.size());
        return generatedFiles;
    }

    /**
     * 推荐最匹配的模板。
     *
     * @param techStack 技术栈描述
     * @return 推荐模板 ID
     */
    public String recommendTemplate(String techStack) {
        String recommendation = chatModel.generate(
                "你是一个模板推荐助手。用户需要以下技术栈的脚手架模板:\n" +
                techStack + "\n" +
                "从以下模板中选择最匹配的一个，只返回模板 id:\n" +
                "- spring-boot-3-mysql: Spring Boot 3 + MySQL\n" +
                "- spring-boot-3-postgres: Spring Boot 3 + PostgreSQL\n" +
                "- go-gin-redis: Go + Gin + Redis\n" +
                "- nestjs-mongo: NestJS + MongoDB\n" +
                "- react-ts: React + TypeScript 前端\n"
        ).trim();
        return recommendation;
    }

    private Path findTemplate(String templateId) {
        // 模板存储目录
        Path templatesDir = Paths.get("data", "templates");
        return templatesDir.resolve(templateId + ".zip");
    }

    private String replaceVariables(String content, Map<String, String> variables) {
        String result = content;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }
}
