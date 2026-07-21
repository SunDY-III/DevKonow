package com.devknow.study;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KnowledgePointRepository extends JpaRepository<KnowledgePoint, Long> {
    List<KnowledgePoint> findByRelatedProjectIdOrderByCreatedAtDesc(Long projectId);
    List<KnowledgePoint> findByStatusOrderByCreatedAtDesc(String status);
    long countByRelatedProjectId(Long projectId);
}
