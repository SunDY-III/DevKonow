package com.devknow.codeindex;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GitCommitRepository extends JpaRepository<GitCommitEntity, Long> {
    List<GitCommitEntity> findByProjectIdAndIncidentTrue(Long projectId);
    void deleteByProjectId(Long projectId);
    Optional<GitCommitEntity> findByProjectIdAndCommitHash(Long projectId, String commitHash);
}
