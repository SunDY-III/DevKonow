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
 * 架构图数据提取服务。
 *
 * <p>从 code_unit 表中读取项目方法数据，调用 ArchitectureAnalyzer
 * 分析并生成 Mermaid 格式架构图数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArchitectureExtractionService {

    private final CodeProjectRepository projectRepository;
    private final CodeUnitEntityRepository codeUnitRepo;
    private final ArchitectureAnalyzer architectureAnalyzer;

    /**
     * 提取项目架构信息。
     *
     * @param projectId 项目 ID
     * @return 架构分析结果
     */
    public CodeAnalyzer.AnalysisResult extract(Long projectId) {
        CodeProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + projectId));

        List<CodeUnitEntity> units = codeUnitRepo.findByProjectId(projectId);

        if (units.isEmpty()) {
            log.warn("项目无代码单元，无法分析架构: projectId={}", projectId);
            return CodeAnalyzer.AnalysisResult.builder().build();
        }

        log.info("开始提取架构: projectId={}, units={}", projectId, units.size());
        CodeAnalyzer.AnalysisResult result = architectureAnalyzer.analyze(project, units);

        // 缓存结果到分析器返回
        return result;
    }

    /**
     * 获取缓存的架构图数据（Mermaid 格式）。
     */
    public String getDiagramData(Long projectId) {
        CodeAnalyzer.AnalysisResult result = extract(projectId);
        if (result.getArchitecture() != null) {
            return result.getArchitecture().getDiagramData();
        }
        return "";
    }
}
