package com.zhishu.codeindex;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GitCommitRepository extends JpaRepository<GitCommitEntity, Long> {
    List<GitCommitEntity> findByProjectIdAndIncidentTrue(Long projectId);
    void deleteByProjectId(Long projectId);
}
