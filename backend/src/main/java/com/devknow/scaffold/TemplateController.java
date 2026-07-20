package com.devknow.scaffold;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 模板与脚手架 API。
 *
 * <ul>
 *   <li>GET /api/templates/list - 模板列表</li>
 *   <li>GET /api/templates/{id} - 模板详情</li>
 *   <li>POST /api/scaffold/generate - 生成脚手架（SSE）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    @GetMapping("/api/templates/list")
    public ResponseEntity<List<TemplateMetadata>> listTemplates() {
        return ResponseEntity.ok(templateService.listTemplates());
    }

    @GetMapping("/api/templates/{id}")
    public ResponseEntity<TemplateMetadata> getTemplate(@PathVariable String id) {
        return ResponseEntity.ok(templateService.getTemplate(id));
    }

    @PostMapping(value = "/api/scaffold/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generate(
            @RequestBody Map<String, Object> body) {
        String templateId = (String) body.get("templateId");
        @SuppressWarnings("unchecked")
        Map<String, String> variables = (Map<String, String>) body.get("variables");
        String projectName = (String) body.getOrDefault("projectName", "my-project");
        return templateService.generate(templateId, variables, projectName);
    }
}
