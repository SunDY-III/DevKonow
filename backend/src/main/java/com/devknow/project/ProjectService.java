package com.devknow.project;

import com.devknow.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 项目管理服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final CodeProjectRepository projectRepository;

    public List<CodeProject> listProjects() {
        return projectRepository.findByStatus("ACTIVE");
    }

    public CodeProject getProject(Long id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new BizException("项目不存在: " + id));
    }

    public void deleteProject(Long id) {
        CodeProject project = getProject(id);
        project.setStatus("ARCHIVED");
        projectRepository.save(project);
    }

}
