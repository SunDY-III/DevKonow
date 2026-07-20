package com.devknow.mentor;

import lombok.Data;

import java.util.List;

/** 学习进度摘要。 */
@Data
public class MentorProgress {
    private Long projectId;
    private Long userId;
    private List<String> completedSections;
    private int masteryLevel; // 1-5
    private int totalSections;
    private double percentComplete;
}
