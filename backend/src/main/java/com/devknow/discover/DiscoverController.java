package com.devknow.discover;

import com.devknow.common.UserContext;
import com.devknow.discover.DiscoverService.DiscoverResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 发现推荐 REST API。
 *
 * <p>用户学习意图发现 + 只读导入学习项目。
 */
@Slf4j
@RestController
@RequestMapping("/api/discover")
@RequiredArgsConstructor
public class DiscoverController {

    private final DiscoverService discoverService;

    /**
     * 发现推荐：用户描述想学什么，返回推荐项目列表。
     *
     * @param request { query: "想学微服务网关" }
     * @return 推荐结果（项目列表 + 意图解析）
     */
    @PostMapping("/search")
    public ResponseEntity<DiscoverResult> discover(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Long userId = UserContext.require();
        DiscoverResult result = discoverService.discover(userId, query);
        return ResponseEntity.ok(result);
    }

    /**
     * 以只读模式导入学习项目（SSE 进度推送）。
     * 使用 GET + query param 以兼容前端 EventSource。
     */
    @GetMapping(value = "/import", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter importLearningProject(@RequestParam String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("message", "仓库地址不能为空")));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }
        return discoverService.importLearningProject(repoUrl);
    }
}
