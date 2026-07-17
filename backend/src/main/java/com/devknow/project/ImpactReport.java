package com.devknow.project;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** Git 变更影响分析报告 */
@Data
@Builder
@AllArgsConstructor
public class ImpactReport {
    private boolean success;
    private String error;
    private Long projectId;
    private String commitHash;
    private String commitMessage;
    private String authorName;
    private List<String> changedFiles;
    private List<String> changedMethods;
    private Map<String, List<String>> affectedCallers;
    private String llmReport;

    public static ImpactReport failed(String error) {
        return ImpactReport.builder().success(false).error(error).build();
    }
}
