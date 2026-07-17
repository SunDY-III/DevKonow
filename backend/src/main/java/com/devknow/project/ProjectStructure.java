package com.devknow.project;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 项目结构扫描结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectStructure {

    /** 主语言，如 "Java"、"Go"、"Python" */
    private String mainLanguage;

    /** 构建工具，如 "Maven"、"Gradle"、"Go Mod" */
    private String buildTool;

    /** 框架，如 "Spring Boot 3.x"、"Gin" */
    private String framework;

    /** 入口点列表，如 ["TradeApplication.java:32"] */
    private List<String> entryPoints;

    /** 模块列表 */
    private List<ModuleInfo> modules;

    /** 文件列表 */
    private List<FileInfo> files;

    /** 总文件数 */
    private int totalFiles;

    /** 各语言文件数统计，如 {java: 180, xml: 30, yml: 12} */
    private java.util.Map<String, Integer> languageCounts;
}
