package com.devknow.study;

import com.devknow.common.ApiResponse;
import com.devknow.common.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
}
