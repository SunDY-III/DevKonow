package com.devknow.study;

import com.devknow.codeindex.CodeUnitEntity;
import com.devknow.codeindex.CodeUnitEntityRepository;
import com.devknow.project.CodeProject;
import com.devknow.project.CodeProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 设计模式检测服务。
 *
 * <p>复用 ArchitectureAnalyzer 的合并 LLM 调用结果，
 * 提取其中的设计模式信息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatternDetectionService {

    private final CodeProjectRepository projectRepository;
    private final CodeUnitEntityRepository codeUnitRepo;
    private final ArchitectureAnalyzer architectureAnalyzer;

    /**
     * 检测项目中的设计模式。
     *
     * @param projectId 项目 ID
     * @return 设计模式列表
     */
    public List<CodeAnalyzer.Pattern> detectPatterns(Long projectId) {
        CodeProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + projectId));

        List<CodeUnitEntity> units = codeUnitRepo.findByProjectId(projectId);

        if (units.isEmpty()) {
            log.warn("项目无代码单元，无法检测模式: projectId={}", projectId);
            return List.of();
        }

        CodeAnalyzer.AnalysisResult result = architectureAnalyzer.analyze(project, units);
        return result.getPatterns() != null ? result.getPatterns() : List.of();
    }
}
