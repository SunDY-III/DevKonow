package com.zhishu.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CodeProjectRepository extends JpaRepository<CodeProject, Long> {
    List<CodeProject> findByStatus(String status);
}
