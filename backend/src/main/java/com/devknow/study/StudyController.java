package com.devknow.study;

import com.devknow.common.ApiResponse;
import com.devknow.common.UserContext;
import com.devknow.project.CodeProject;
import com.devknow.project.CodeProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 研读 API 控制器。
 *
 * <p>提供研读相关的 SSE 流式端点与 REST 端点。
 */
@Slf4j
@RestController
@RequestMapping("/api/study")
@RequiredArgsConstructor
public class StudyController {

    private final ArchitectureExtractionService architectureExtractionService;
    private final PatternDetectionService patternDetectionService;
    private final LearningPathService learningPathService;
    private final KnowledgeExtractor knowledgeExtractor;
    private final CodeProjectRepository projectRepository;

    /**
     * 获取项目架构分析结果。
     */
    @GetMapping("/{projectId}/architecture")
    public ApiResponse<CodeAnalyzer.AnalysisResult> getArchitecture(@PathVariable Long projectId) {
        UserContext.require();
        CodeAnalyzer.AnalysisResult result = architectureExtractionService.extract(projectId);
        return ApiResponse.ok(result);
    }

    /**
     * 获取项目架构图数据（Mermaid 格式）。
     */
    @GetMapping("/{projectId}/diagram")
    public ApiResponse<Map<String, String>> getDiagram(@PathVariable Long projectId) {
        UserContext.require();
        String diagramData = architectureExtractionService.getDiagramData(projectId);
        return ApiResponse.ok(Map.of("diagram", diagramData));
    }

    /**
     * 获取项目设计模式检测结果。
     */
    @GetMapping("/{projectId}/patterns")
    public ApiResponse<List<CodeAnalyzer.Pattern>> getPatterns(@PathVariable Long projectId) {
        UserContext.require();
        List<CodeAnalyzer.Pattern> patterns = patternDetectionService.detectPatterns(projectId);
        return ApiResponse.ok(patterns);
    }

    /**
     * 获取项目代码高亮。
     */
    @GetMapping("/{projectId}/highlights")
    public ApiResponse<List<CodeAnalyzer.Highlight>> getHighlights(@PathVariable Long projectId) {
        UserContext.require();
        CodeAnalyzer.AnalysisResult result = architectureExtractionService.extract(projectId);
        return ApiResponse.ok(result.getHighlights());
    }

    /**
     * 获取学习路线图（L1~L5）。
     */
    @GetMapping("/roadmap")
    public ApiResponse<List<LearningPathService.StudyLevel>> getRoadmap() {
        return ApiResponse.ok(learningPathService.getRoadmap());
    }

    /**
     * 对问题进行层级分类。
     */
    @PostMapping("/classify")
    public ApiResponse<Map<String, Object>> classify(@RequestBody Map<String, String> body) {
        String question = body.getOrDefault("question", "");
        if (question.isBlank()) {
            return ApiResponse.fail(400, "question is required");
        }
        var levelResult = learningPathService.classifyQuestion(question);
        var levelInfo = learningPathService.getLevelInfo(levelResult.getLevel());
        return ApiResponse.ok(Map.of(
                "level", levelResult.getLevel(),
                "confidence", levelResult.getConfidence(),
                "reason", levelResult.getReason(),
                "levelName", levelInfo.getName(),
                "advice", levelInfo.getAdvice()
        ));
    }

    /**
     * 综合研读 - 获取项目全量分析结果。
     */
    @GetMapping("/{projectId}/overview")
    public ApiResponse<Map<String, Object>> getOverview(@PathVariable Long projectId) {
        UserContext.require();
        CodeAnalyzer.AnalysisResult result = architectureExtractionService.extract(projectId);
        List<CodeAnalyzer.Pattern> patterns = result.getPatterns();
        List<CodeAnalyzer.Highlight> highlights = result.getHighlights();

        return ApiResponse.ok(Map.of(
                "architecture", result.getArchitecture(),
                "highlights", highlights != null ? highlights : List.of(),
                "patterns", patterns != null ? patterns : List.of(),
                "roadmap", learningPathService.getRoadmap()
        ));
    }

    // ==================== 知识提取 ====================

    /**
     * 从对话中提取知识点（SSE 进度推送）。
     */
    @PostMapping(value = "/extract", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter extractKnowledge(@RequestBody Map<String, Object> request) {
        Long userId = UserContext.require();
        SseEmitter emitter = new SseEmitter(60_000L);

        CompletableFuture.runAsync(() -> {
            try {
                String question = (String) request.get("question");
                String answer = (String) request.get("answer");
                String codeContext = (String) request.getOrDefault("codeContext", null);
                Long projectId = request.get("projectId") != null
                        ? Long.valueOf(request.get("projectId").toString()) : null;

                emitter.send(SseEmitter.event().name("phase").data(Map.of("stage", "extracting")));
                Thread.sleep(200);

                emitter.send(SseEmitter.event().name("phase").data(Map.of("stage", "analyzing")));
                KnowledgePoint kp = knowledgeExtractor.extract(userId, question, answer, codeContext, projectId);

                emitter.send(SseEmitter.event().name("phase").data(Map.of("stage", "formatting")));
                emitter.send(SseEmitter.event().name("result").data(Map.of(
                        "id", kp.getId(),
                        "title", kp.getTitle(),
                        "concept", kp.getConcept(),
                        "difficultyLevel", kp.getDifficultyLevel(),
                        "patternName", kp.getPatternName() != null ? kp.getPatternName() : ""
                )));
                emitter.complete();
            } catch (Exception e) {
                try { emitter.send(SseEmitter.event().name("error").data(Map.of("message", e.getMessage()))); }
                catch (Exception ignored) {}
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        });

        return emitter;
    }

    /**
     * 获取项目的所有知识点。
     */
    @GetMapping("/{projectId}/knowledge-points")
    public ApiResponse<Map<String, Object>> getKnowledgePoints(@PathVariable Long projectId) {
        List<KnowledgePoint> points = knowledgeExtractor.getProjectKnowledgePoints(projectId);
        return ApiResponse.ok(Map.of("knowledgePoints", points, "total", points.size()));
    }

    /**
     * 获取学习进度统计。
     */
    @GetMapping("/{projectId}/stats")
    public ApiResponse<Map<String, Object>> getStats(@PathVariable Long projectId) {
        Map<String, Object> stats = knowledgeExtractor.getStats(projectId);
        return ApiResponse.ok(stats);
    }

    /**
     * 获取技能树数据。
     */
    @GetMapping("/{projectId}/skill-tree")
    public ApiResponse<Map<String, Object>> getSkillTree(@PathVariable Long projectId) {
        List<KnowledgePoint> points = knowledgeExtractor.getProjectKnowledgePoints(projectId);
        List<Map<String, Object>> nodes = points.stream().map(kp -> Map.<String, Object>of(
                "id", kp.getId().toString(),
                "title", kp.getTitle(),
                "difficultyLevel", kp.getDifficultyLevel(),
                "feynmanPassCount", kp.getFeynmanPassCount() != null ? kp.getFeynmanPassCount() : 0,
                "reviewCount", kp.getReviewCount() != null ? kp.getReviewCount() : 0
        )).toList();
        return ApiResponse.ok(Map.of("nodes", nodes));
    }

    /**
     * 学习概览（用于作品集）。
     */
    @GetMapping("/progress-overview")
    public ApiResponse<Map<String, Object>> getProgressOverview() {
        List<CodeProject> projects = projectRepository.findByStatus("ACTIVE");
        return ApiResponse.ok(Map.of(
                "totalProjects", projects.size(),
                "projects", projects.stream().map(p -> Map.of(
                        "id", p.getId(),
                        "name", p.getName(),
                        "language", p.getLanguage() != null ? p.getLanguage() : ""
                )).toList()
        ));
    }
}
