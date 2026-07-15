package com.devknow.controller;

import com.devknow.codeindex.CodeIndexMode;
import com.devknow.codeindex.CodeIndexModeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 代码索引模式管理 API。
 *
 * <p>运行时切换 Tree-sitter / SCIP 模式，无需重启。
 * SCIP 模式下自动检测并生成索引，通过 SSE 推送进度。
 */
@Slf4j
@RestController
@RequestMapping("/api/codeindex")
@RequiredArgsConstructor
public class CodeIndexController {

    private final CodeIndexModeService modeService;

    /**
     * 获取当前索引模式。
     */
    @GetMapping("/mode")
    public ResponseEntity<Map<String, Object>> getMode() {
        CodeIndexMode mode = modeService.getCurrentMode();
        String status = modeService.getSwitchingStatus();
        return ResponseEntity.ok(Map.of(
                "mode", mode.name().toLowerCase(),
                "switching", !"idle".equals(status),
                "status", status
        ));
    }

    /**
     * 切换索引模式。
     *
     * <p>body: {"mode": "scip", "projectDir": "/path/to/project"}
     * mode: tree-sitter | scip
     * projectDir: SCIP 模式时需要，用于索引生成
     */
    @PutMapping("/mode")
    public ResponseEntity<Map<String, Object>> switchMode(@RequestBody Map<String, String> body) {
        String modeStr = body.get("mode");
        if (modeStr == null || modeStr.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "mode 不能为空"));
        }

        CodeIndexMode target;
        if ("scip".equalsIgnoreCase(modeStr)) {
            target = CodeIndexMode.SCIP;
        } else if ("tree-sitter".equalsIgnoreCase(modeStr) || "tree_sitter".equalsIgnoreCase(modeStr)) {
            target = CodeIndexMode.TREE_SITTER;
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "无效模式: " + modeStr
                    + "，可选: tree-sitter / scip"));
        }

        String projectDir = body.get("projectDir");
        if (target == CodeIndexMode.SCIP && (projectDir == null || projectDir.isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "SCIP 模式需要 projectDir"));
        }

        CodeIndexModeService.SwitchResult result = modeService.switchMode(target, projectDir);

        Map<String, Object> resp = Map.of(
                "success", result.success(),
                "message", result.message(),
                "async", result.async(),
                "mode", target.name().toLowerCase()
        );
        return result.success() ? ResponseEntity.ok(resp)
                : ResponseEntity.badRequest().body(resp);
    }

    /**
     * SSE 订阅模式切换和 SCIP 索引生成进度。
     * 首次连接立即推送当前状态。
     */
    @GetMapping(value = "/mode/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeProgress() {
        return modeService.subscribeProgress();
    }
}
