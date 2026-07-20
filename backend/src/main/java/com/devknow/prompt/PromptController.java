package com.devknow.prompt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Prompt 模板管理 API。
 *
 * <ul>
 *   <li>GET /api/admin/prompts - prompt 模板列表</li>
 *   <li>GET /api/admin/prompts/{id} - 获取单个模板</li>
 *   <li>PUT /api/admin/prompts/{id} - 更新 prompt 内容</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/prompts")
@RequiredArgsConstructor
public class PromptController {

    private final PromptService promptService;

    @GetMapping
    public ResponseEntity<List<PromptTemplate>> listPrompts() {
        return ResponseEntity.ok(promptService.listPrompts());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PromptTemplate> getPrompt(@PathVariable Long id) {
        return ResponseEntity.ok(promptService.getPrompt(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PromptTemplate> updatePrompt(
            @PathVariable Long id,
            @RequestBody PromptTemplate update) {
        try {
            return ResponseEntity.ok(promptService.updatePrompt(id, update));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).build();
        }
    }
}
