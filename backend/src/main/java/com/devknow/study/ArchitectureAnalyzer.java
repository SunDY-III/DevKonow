package com.devknow.study;

import com.devknow.codeindex.CodeUnitEntity;
import com.devknow.project.CodeProject;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 架构分析器 - 统一多任务 LLM 调用。
 *
 * <p>将架构提取、代码高亮、模式识别合并为单次 LLM 调用，
 * 返回结构化 JSON 结果。
 */
@Slf4j
@Component
public class ArchitectureAnalyzer {

    private final ChatLanguageModel chatModel;
    private final ObjectMapper objectMapper;

    public ArchitectureAnalyzer(ChatLanguageModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    /**
     * 综合分析：架构 + 高亮 + 模式，单次 LLM 调用。
     */
    @SuppressWarnings("unchecked")
    public CodeAnalyzer.AnalysisResult analyze(CodeProject project, List<CodeUnitEntity> units) {
        if (units == null || units.isEmpty()) {
            return CodeAnalyzer.AnalysisResult.builder().build();
        }

        long start = System.currentTimeMillis();

        // 摘要项目结构信息
        String structureSummary = buildStructureSummary(project, units);

        String prompt = String.format("""
                你是一个代码分析专家。请根据以下项目结构和方法列表，完成三项分析任务。
                以 JSON 格式输出，必须严格遵循给定的结构。

                项目信息：
                - 名称：%s
                - 语言：%s
                - 框架：%s
                - 总文件数：%d
                - 总方法数：%d

                代码单元（前50条，按文件分组）：
                %s

                请输出以下 JSON 结构（只返回 JSON，不要 markdown 代码块标记）：
                {
                  "architecture": {
                    "summary": "整体架构描述（100字以内）",
                    "modules": [
                      {"name": "模块名", "description": "功能描述", "responsibilities": ["职责1","职责2"], "techStack": "技术栈"}
                    ],
                    "relations": [
                      {"source": "模块A", "target": "模块B", "type": "call/depend/inherit/implement", "description": "关系描述"}
                    ],
                    "diagramData": "Mermaid 格式架构图（erDiagram 或 graph TD）"
                  },
                  "highlights": [
                    {"title": "亮点标题", "description": "为什么这个值得关注", "filePath": "文件路径", "startLine": 1, "endLine": 10, "codeSnippet": "关键代码片段", "relevance": "critical/important/good_to_know"}
                  ],
                  "patterns": [
                    {"name": "模式名", "description": "模式说明", "category": "creational/structural/behavioral/architectural", "participants": ["参与类/文件"], "benefit": "收益"}
                  ]
                }

                注意：
                - modules 至少 1 个，最多 10 个
                - highlights 最多 5 条
                - patterns 最多 5 条
                - diagramData 使用 Mermaid 语法，描述模块间关系
                """,
                project.getDisplayName(),
                project.getLanguage(),
                project.getFramework(),
                project.getTotalFiles() != null ? project.getTotalFiles() : 0,
                project.getTotalMethods() != null ? project.getTotalMethods() : 0,
                structureSummary
        );

        try {
            String response = chatModel.chat(
        ChatRequest.builder()
            .messages(List.of(UserMessage.from(prompt)))
            .build())
        .aiMessage().text();
            String json = extractJson(response);

            Map<String, Object> resultMap = objectMapper.readValue(json, Map.class);

            // 解析架构
            CodeAnalyzer.ArchitectureInfo archInfo = parseArchitecture(
                    (Map<String, Object>) resultMap.getOrDefault("architecture", Map.of()));

            // 解析高亮
            List<CodeAnalyzer.Highlight> highlights = parseHighlights(
                    (List<Map<String, Object>>) resultMap.getOrDefault("highlights", List.of()));

            // 解析模式
            List<CodeAnalyzer.Pattern> patterns = parsePatterns(
                    (List<Map<String, Object>>) resultMap.getOrDefault("patterns", List.of()));

            log.info("代码分析完成: projectId={}, modules={}, highlights={}, patterns={}, 耗时={}ms",
                    project.getId(), archInfo.getModules().size(), highlights.size(), patterns.size(),
                    System.currentTimeMillis() - start);

            return CodeAnalyzer.AnalysisResult.builder()
                    .architecture(archInfo)
                    .highlights(highlights)
                    .patterns(patterns)
                    .build();

        } catch (Exception e) {
            log.warn("综合分析失败: {}", e.getMessage());
            return CodeAnalyzer.AnalysisResult.builder().build();
        }
    }

    private String buildStructureSummary(CodeProject project, List<CodeUnitEntity> units) {
        // 按文件分组统计
        Map<String, List<CodeUnitEntity>> byFile = units.stream()
                .collect(Collectors.groupingBy(
                        u -> u.getFilePath() != null ? u.getFilePath() : "unknown",
                        Collectors.toList()));

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, List<CodeUnitEntity>> entry : byFile.entrySet()) {
            if (count++ >= 30) {
                sb.append("... 还有 ").append(byFile.size() - 30).append(" 个文件\n");
                break;
            }
            sb.append("文件: ").append(entry.getKey()).append("\n");
            for (CodeUnitEntity u : entry.getValue().subList(0, Math.min(entry.getValue().size(), 5))) {
                sb.append("  - ").append(u.getMethodName())
                  .append(" (").append(u.getSignature()).append(")")
                  .append(" [L").append(u.getStartLine()).append("-L").append(u.getEndLine()).append("]");
                if (u.getComment() != null && !u.getComment().isBlank()) {
                    sb.append(" // ").append(truncate(u.getComment(), 60));
                }
                sb.append("\n");
            }
            if (entry.getValue().size() > 5) {
                sb.append("  ... 还有 ").append(entry.getValue().size() - 5).append(" 个方法\n");
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private CodeAnalyzer.ArchitectureInfo parseArchitecture(Map<String, Object> archMap) {
        if (archMap == null || archMap.isEmpty()) {
            return CodeAnalyzer.ArchitectureInfo.builder()
                    .modules(List.of())
                    .relations(List.of())
                    .build();
        }

        List<Map<String, Object>> modulesRaw = (List<Map<String, Object>>) archMap.getOrDefault("modules", List.of());
        List<Map<String, Object>> relationsRaw = (List<Map<String, Object>>) archMap.getOrDefault("relations", List.of());

        List<CodeAnalyzer.Module> modules = modulesRaw.stream().map(m ->
            CodeAnalyzer.Module.builder()
                    .name((String) m.getOrDefault("name", ""))
                    .description((String) m.getOrDefault("description", ""))
                    .responsibilities((List<String>) m.getOrDefault("responsibilities", List.of()))
                    .techStack((String) m.getOrDefault("techStack", ""))
                    .build()
        ).toList();

        List<CodeAnalyzer.Relation> relations = relationsRaw.stream().map(r ->
            CodeAnalyzer.Relation.builder()
                    .source((String) r.getOrDefault("source", ""))
                    .target((String) r.getOrDefault("target", ""))
                    .type((String) r.getOrDefault("type", ""))
                    .description((String) r.getOrDefault("description", ""))
                    .build()
        ).toList();

        return CodeAnalyzer.ArchitectureInfo.builder()
                .summary((String) archMap.getOrDefault("summary", ""))
                .modules(modules)
                .relations(relations)
                .diagramData((String) archMap.getOrDefault("diagramData", ""))
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<CodeAnalyzer.Highlight> parseHighlights(List<Map<String, Object>> highlightsRaw) {
        if (highlightsRaw == null) return List.of();
        return highlightsRaw.stream().map(h ->
            CodeAnalyzer.Highlight.builder()
                    .title((String) h.getOrDefault("title", ""))
                    .description((String) h.getOrDefault("description", ""))
                    .filePath((String) h.getOrDefault("filePath", ""))
                    .startLine(h.get("startLine") != null ? ((Number) h.get("startLine")).intValue() : null)
                    .endLine(h.get("endLine") != null ? ((Number) h.get("endLine")).intValue() : null)
                    .codeSnippet((String) h.getOrDefault("codeSnippet", ""))
                    .relevance((String) h.getOrDefault("relevance", "good_to_know"))
                    .build()
        ).toList();
    }

    @SuppressWarnings("unchecked")
    private List<CodeAnalyzer.Pattern> parsePatterns(List<Map<String, Object>> patternsRaw) {
        if (patternsRaw == null) return List.of();
        return patternsRaw.stream().map(p ->
            CodeAnalyzer.Pattern.builder()
                    .name((String) p.getOrDefault("name", ""))
                    .description((String) p.getOrDefault("description", ""))
                    .category((String) p.getOrDefault("category", ""))
                    .participants((List<String>) p.getOrDefault("participants", List.of()))
                    .benefit((String) p.getOrDefault("benefit", ""))
                    .build()
        ).toList();
    }

    private String extractJson(String response) {
        if (response == null) return "{}";
        String json = response;
        if (json.contains("```json")) {
            json = json.substring(json.indexOf("```json") + 7);
            json = json.substring(0, json.indexOf("```"));
        } else if (json.contains("```")) {
            json = json.substring(json.indexOf("```") + 3);
            json = json.substring(0, json.indexOf("```"));
        }
        return json.trim();
    }

    private String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
