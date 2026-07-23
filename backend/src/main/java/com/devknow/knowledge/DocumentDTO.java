package com.devknow.knowledge;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档 DTO —— 对外 API 返回的文档信息。
 *
 * <p>与 JPA Entity {@link KnowledgeDocument} 分离，避免直接暴露
 * 内部字段（如 objectKey、fileMd5、deleted 等）。
 */
@Data
@Builder
@AllArgsConstructor
public class DocumentDTO {
    private Long id;
    private String fileName;
    private String status;
    private Integer chunkCount;
    private Integer level;
    private String tags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 从 Entity 转换为 DTO。
     */
    public static DocumentDTO from(KnowledgeDocument doc) {
        if (doc == null) return null;
        return DocumentDTO.builder()
                .id(doc.getId())
                .fileName(doc.getFileName())
                .status(doc.getStatus())
                .chunkCount(doc.getChunkCount())
                .level(doc.getLevel())
                .tags(doc.getTags())
                .createdAt(doc.getCreatedAt())
                .updatedAt(doc.getUpdatedAt())
                .build();
    }
}
