package com.zhishu.codeindex;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Entity @Table(name = "git_commit", indexes = {
    @Index(name = "idx_project", columnList = "projectId")
})
public class GitCommitEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "project_id", nullable = false)
    private Long projectId;
    @Column(length = 40) private String commitHash;
    @Column(length = 128) private String authorName;
    @Column(length = 255) private String authorEmail;
    @Column(columnDefinition = "TEXT") private String message;
    @Column(columnDefinition = "TEXT") private String diffSummary;
    private boolean incident;
    @Column(length = 16) private String severity;
    private LocalDateTime committedAt;
    private LocalDateTime createdAt;

    @PrePersist void onCreate() { createdAt = LocalDateTime.now(); }
}
