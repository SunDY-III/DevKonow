package com.devknow.study;

import com.devknow.common.ApiResponse;
import com.devknow.common.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/study/quality")
@RequiredArgsConstructor
public class CodeQualityController {

    private final CodeQualityService codeQualityService;

    @PostMapping("/score")
    public ApiResponse<CodeQualityService.QualityReport> score(@RequestBody Map<String, String> request) {
        UserContext.require();
        String code = request.get("code");
        String language = request.getOrDefault("language", "未知");
        String context = request.getOrDefault("context", null);

        if (code == null || code.isBlank()) {
            return ApiResponse.fail(400, "code is required");
        }

        CodeQualityService.QualityReport report = codeQualityService.score(code, language, context);
        return ApiResponse.ok(report);
    }
}
