package com.zhishu.codeindex;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * CodeUnit JPA Repository。
 *
 * <p>除了基础 CRUD，还提供波及重建所需的反向调用链查询：
 * <ul>
 *   <li>{@link #findCallersByMethodName} — 查"谁调了指定方法"</li>
 *   <li>{@link #findFilePathsByProjectId} — 查某项目的所有文件路径</li>
 *   <li>{@link #deleteByProjectId} — 删除项目的所有索引数据</li>
 * </ul>
 */
public interface CodeUnitEntityRepository extends JpaRepository<CodeUnitEntity, Long> {

    /**
     * 波及重建核心查询：找出调用了指定方法名的所有文件。
     * 同时匹配 calls（Tree-sitter 语法级）和 enriched_calls（JavaEnhancer 类.方法级）。
     *
     * @param projectId  项目 ID
     * @param methodName 被调用的方法名
     * @return 调用方文件的路径列表（去重）
     */
    @Query(value = """
        SELECT DISTINCT c.file_path FROM code_unit c
        WHERE c.project_id = :projectId
          AND (c.calls LIKE %:methodName%
               OR c.enriched_calls LIKE %:methodName%)
        """, nativeQuery = true)
    List<String> findCallersByMethodName(@Param("projectId") Long projectId,
                                          @Param("methodName") String methodName);

    /**
     * 查某项目的所有已索引文件路径。
     */
    @Query(value = "SELECT DISTINCT c.file_path FROM code_unit c WHERE c.project_id = :projectId",
            nativeQuery = true)
    List<String> findFilePathsByProjectId(@Param("projectId") Long projectId);

    /**
     * 删除某项目的所有索引记录。
     */
    void deleteByProjectId(Long projectId);

    /**
     * 按文件路径查找 CodeUnit。
     */
    List<CodeUnitEntity> findByProjectIdAndFilePath(Long projectId, String filePath);
}
