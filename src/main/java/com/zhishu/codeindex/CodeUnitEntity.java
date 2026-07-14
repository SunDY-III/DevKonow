package com.zhishu.codeindex;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * CodeUnit 的 JPA 实体（code_unit 表）。
 * 存储每个方法的结构化数据，用于关键词检索 + 反向调用链查询。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "code_unit", indexes = {
    @Index(name = "idx_project", columnList = "projectId"),
    @Index(name = "idx_file", columnList = "projectId, filePath"),
    @Index(name = "idx_method", columnList = "projectId, methodName")
})
public class CodeUnitEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "file_path", nullable = false, length = 512)
    private String filePath;

    @Column(length = 255)
    private String packageName;

    @Column(length = 255)
    private String className;

    @Column(length = 255)
    private String methodName;

    @Column(columnDefinition = "TEXT")
    private String signature;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String body;

    @Column(name = "start_line", nullable = false)
    private int startLine;

    @Column(name = "end_line", nullable = false)
    private int endLine;

    /** Tree-sitter 提取的调用列表（JSON 数组） */
    @Column(columnDefinition = "TEXT")
    private String calls;

    /** JavaEnhancer 增强后的精确调用链（JSON 数组） */
    @Column(name = "enriched_calls", columnDefinition = "TEXT")
    private String enrichedCalls;

    /** 类型全名，如 "com.trade.service.OrderService" */
    @Column(length = 512)
    private String resolvedType;

    /** 注解列表（JSON 数组） */
    @Column(columnDefinition = "TEXT")
    private String annotations;

    @Column(length = 16)
    private String language;

    /** 内容 MD5，用于差量检测 */
    @Column(length = 32)
    private String checksum;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
