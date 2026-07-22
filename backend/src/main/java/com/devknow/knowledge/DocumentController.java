package com.devknow.knowledge;

import com.devknow.common.ApiResponse;
import com.devknow.common.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/doc")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/upload")
    public ApiResponse<KnowledgeDocument> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(documentService.upload(UserContext.require(), file));
    }

    @GetMapping("/list")
    public ApiResponse<Page<KnowledgeDocument>> list(Pageable pageable) {
        return ApiResponse.ok(documentService.listMine(UserContext.require(), pageable));
    }

    @GetMapping("/{id}/progress")
    public ApiResponse<Map<String, Object>> progress(@PathVariable Long id) {
        return ApiResponse.ok(documentService.progress(UserContext.require(), id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        documentService.delete(UserContext.require(), id);
        return ApiResponse.ok(null);
    }
}
