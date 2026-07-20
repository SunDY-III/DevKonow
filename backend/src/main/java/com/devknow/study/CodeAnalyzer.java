package com.devknow.study;

import com.devknow.codeindex.CodeUnitEntity;
import com.devknow.project.CodeProject;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 代码分析器统一接口。
 *
 * <p>三种分析任务合并为一次多任务 LLM 调用，减少 Token 消耗。
 */
public interface CodeAnalyzer {

    /**
     * 对项目进行综合分析（架构 + 高亮 + 模式），
     * 内部合并为一次 LLM 调用。
     */
    AnalysisResult analyze(CodeProject project, List<CodeUnitEntity> units);

    /** 综合分析结果 */
    @Data
    @Builder
    class AnalysisResult {
        private ArchitectureInfo architecture;
        private List<Highlight> highlights;
        private List<Pattern> patterns;
    }

    /** 架构信息 */
    @Data
    @Builder
    class ArchitectureInfo {
        private String summary;
        private List<Module> modules;
        private List<Relation> relations;
        private String diagramData;
    }

    @Data
    @Builder
    class Module {
        private String name;
        private String description;
        private List<String> responsibilities;
        private String techStack;
    }

    @Data
    @Builder
    class Relation {
        private String source;
        private String target;
        private String type;
        private String description;
    }

    /** 代码高亮 */
    @Data
    @Builder
    class Highlight {
        private String title;
        private String description;
        private String filePath;
        private Integer startLine;
        private Integer endLine;
        private String codeSnippet;
        private String relevance;
    }

    /** 设计模式 */
    @Data
    @Builder
    class Pattern {
        private String name;
        private String description;
        private String category;
        private List<String> participants;
        private String benefit;
    }
}
