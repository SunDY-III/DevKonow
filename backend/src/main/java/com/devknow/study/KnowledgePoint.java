package com.devknow.study;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "knowledge_point")
public class KnowledgePoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String concept;

    @Column(name = "chunk_ids", columnDefinition = "TEXT")
    private String chunkIds;

    @Column(name = "pattern_name", length = 128)
    private String patternName;

    @Column(name = "difficulty_level")
    private Integer difficultyLevel;

    @Column(name = "prerequisite_ids", columnDefinition = "TEXT")
    private String prerequisiteIds;

    @Column(name = "related_project_id")
    private Long relatedProjectId;

    @Column(name = "feynman_pass_count")
    private Integer feynmanPassCount;

    @Column(name = "review_count")
    private Integer reviewCount;

    @Column(name = "source_conversation_id", length = 128)
    private String sourceConversationId;

    @Column(name = "source_question", columnDefinition = "TEXT")
    private String sourceQuestion;

    @Column(length = 32)
    @Builder.Default
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
