package com.devknow.feynman;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class FeynmanSession {
    private String conversationId;
    private Long userId;
    private String question;
    private String originalAnswer;
    private List<String> sourceChunkIds;
    private int round;
    private int correctCount;
    private int failedCount;
    private boolean passed;
    private boolean failed;
    private boolean skipped;
    private String status = "questioning"; // questioning / evaluating / rebuttal / passed / failed
    private List<FeynmanRound> rounds = new ArrayList<>();
    private LocalDateTime createdAt = LocalDateTime.now();

    @Data
    public static class FeynmanRound {
        private int roundNum;
        private String verifyQuestion;
        private String userAnswer;
        private String judgment;
        private boolean correct;
        private String hint;
        private String gapAnalysis;
    }
}
