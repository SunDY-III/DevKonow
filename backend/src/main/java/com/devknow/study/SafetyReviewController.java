package com.devknow.study;

import com.devknow.common.ApiResponse;
import com.devknow.common.UserContext;
import com.devknow.study.SafetyReviewService.SafetyReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/study/safety")
@RequiredArgsConstructor
public class SafetyReviewController {

    private final SafetyReviewService safetyReviewService;

    /**
     * 按文件路径 + 行号范围审查代码。
     *
     * body: { projectId, filePath, startLine, endLine }
     */
    @PostMapping("/review-range")
    public ApiResponse<SafetyReport> reviewByRange(@RequestBody Map<String, Object> request) {
        UserContext.require();
        Long projectId = request.get("projectId") != null
                ? Long.valueOf(request.get("projectId").toString()) : null;
        String filePath = (String) request.get("filePath");
        int startLine = request.get("startLine") != null
                ? Integer.parseInt(request.get("startLine").toString()) : 1;
        int endLine = request.get("endLine") != null
                ? Integer.parseInt(request.get("endLine").toString()) : 1;

        if (filePath == null || filePath.isBlank()) {
            return ApiResponse.fail(400, "filePath is required");
        }

        SafetyReport report = safetyReviewService.review(projectId, filePath, startLine, endLine);
        return ApiResponse.ok(report);
    }
}
