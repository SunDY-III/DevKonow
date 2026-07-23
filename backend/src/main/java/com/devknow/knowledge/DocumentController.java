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
    public ApiResponse<DocumentDTO> upload(@RequestParam("file") MultipartFile file,
                                            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        KnowledgeDocument doc = documentService.upload(UserContext.require(), file);
        return ApiResponse.ok(DocumentDTO.from(doc));
    }

    @GetMapping("/list")
    public ApiResponse<Page<DocumentDTO>> list(Pageable pageable) {
        Page<KnowledgeDocument> docs = documentService.listMine(UserContext.require(), pageable);
        Page<DocumentDTO> dtoPage = docs.map(DocumentDTO::from);
        return ApiResponse.ok(dtoPage);
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
