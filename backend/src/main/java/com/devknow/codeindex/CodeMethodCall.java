package com.devknow.codeindex;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 方法调用关联实体。
 *
 * <p>记录每个方法名→调用方文件的映射关系，替代 {@link CodeUnitEntityRepository#findCallersByMethodName}
 * 中的 LIKE %methodName% 全表扫描查询。
 *
 * <p>数据来源：在 {@link CodeIndexService#saveCodeUnit} 写入 CodeUnit 时，
 * 解析其 calls / enriched_calls 列表，逐条写入此表。
 */
@Entity
@Table(name = "code_method_call", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"project_id", "caller_file", "method_name"})
}, indexes = {
        @Index(name = "idx_method", columnList = "project_id,method_name")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeMethodCall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "caller_file", nullable = false, length = 512)
    private String callerFile;

    @Column(name = "method_name", nullable = false, length = 255)
    private String methodName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
