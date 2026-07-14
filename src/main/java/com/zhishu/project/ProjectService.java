package com.zhishu.project;

import com.zhishu.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

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

    public CodeProject createProject(CodeProject project) {
        return projectRepository.save(project);
    }

    public void deleteProject(Long id) {
        CodeProject project = getProject(id);
        project.setStatus("ARCHIVED");
        projectRepository.save(project);
    }

    /**
     * 获取项目速览信息。
     */
    public Map<String, Object> getProjectSummary(Long id) {
        CodeProject project = getProject(id);
        return Map.of(
                "id", project.getId(),
                "name", project.getName(),
                "displayName", project.getDisplayName(),
                "language", project.getLanguage(),
                "framework", project.getFramework(),
                "buildTool", project.getBuildTool(),
                "totalFiles", project.getTotalFiles(),
                "totalMethods", project.getTotalMethods(),
                "status", project.getStatus(),
                "createdAt", project.getCreatedAt()
        );
    }
}
