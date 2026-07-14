package com.zhishu.project;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 项目实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "code_project")
public class CodeProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 128)
    private String name;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "repo_urls", nullable = false, columnDefinition = "TEXT")
    private String repoUrls;

    @Column(length = 64)
    private String language;

    @Column(length = 128)
    private String framework;

    @Column(name = "build_tool", length = 32)
    private String buildTool;

    @Column(name = "entry_points", columnDefinition = "TEXT")
    private String entryPoints;

    @Column(columnDefinition = "TEXT")
    private String modules;

    @Column(name = "total_files")
    private Integer totalFiles;

    @Column(name = "total_methods")
    private Integer totalMethods;

    @Builder.Default
    @Column(length = 16)
    private String status = "ACTIVE";

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
