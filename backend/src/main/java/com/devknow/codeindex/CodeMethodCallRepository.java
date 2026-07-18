package com.devknow.codeindex;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 方法调用关联 Repository。
 *
 * <p>提供基于索引的高效反向调用链查询，替代 {@link CodeUnitEntityRepository#findCallersByMethodName}
 * 中的 LIKE %methodName% 全表扫描。
 */
public interface CodeMethodCallRepository extends JpaRepository<CodeMethodCall, Long> {

    /**
     * 查"谁调了指定方法"——走 idx_method 索引，O(log n)。
     */
    @Query("SELECT DISTINCT c.callerFile FROM CodeMethodCall c WHERE c.projectId = :projectId AND c.methodName = :methodName")
    List<String> findCallersByMethodName(@Param("projectId") Long projectId, @Param("methodName") String methodName);

    /**
     * 批量查询多个方法的调用方。
     */
    @Query("SELECT DISTINCT c.callerFile FROM CodeMethodCall c WHERE c.projectId = :projectId AND c.methodName IN :methodNames")
    List<String> findCallersByMethodNames(@Param("projectId") Long projectId, @Param("methodNames") List<String> methodNames);

    /**
     * 删除某项目的所有记录（全量重建时用）。
     */
    @Modifying
    @Query("DELETE FROM CodeMethodCall c WHERE c.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);

    /**
     * 删除某文件中所有方法产生的调用记录（增量重建时用）。
     */
    @Modifying
    @Query("DELETE FROM CodeMethodCall c WHERE c.projectId = :projectId AND c.callerFile = :callerFile")
    void deleteByCallerFile(@Param("projectId") Long projectId, @Param("callerFile") String callerFile);
}
