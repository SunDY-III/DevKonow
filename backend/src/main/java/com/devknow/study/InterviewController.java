package com.devknow.study;

import com.devknow.common.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/study/interview")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;

    @PostMapping("/questions")
    public ResponseEntity<Map<String, Object>> generateQuestions(@RequestBody Map<String, Object> request) {
        Long projectId = request.get("projectId") != null ? Long.valueOf(request.get("projectId").toString()) : null;
        String projectName = (String) request.getOrDefault("projectName", "项目");
        String architecture = (String) request.getOrDefault("architecture", null);
        @SuppressWarnings("unchecked")
        List<String> patterns = (List<String>) request.getOrDefault("patterns", List.of());
        String style = (String) request.getOrDefault("style", "gentle");
        Long userId = UserContext.require();

        List<InterviewService.InterviewQuestion> questions =
                interviewService.generateQuestions(projectId, projectName, architecture, patterns, style);
        return ResponseEntity.ok(Map.of("questions", questions, "count", questions.size()));
    }

    @PostMapping("/follow-up")
    public ResponseEntity<Map<String, Object>> followUp(@RequestBody Map<String, String> request) {
        Long userId = UserContext.require();
        String question = request.get("question");
        String userAnswer = request.get("userAnswer");
        String expectedAnswer = request.getOrDefault("expectedAnswer", "");
        int depth = Integer.parseInt(request.getOrDefault("depth", "1"));
        String style = request.getOrDefault("style", "gentle");

        String followUp = interviewService.generateFollowUp(userId, question, userAnswer, expectedAnswer, depth, style);
        return ResponseEntity.ok(Map.of("followUp", followUp));
    }

    @PostMapping("/feedback")
    public ResponseEntity<InterviewService.InterviewFeedback> feedback(@RequestBody Map<String, Object> request) {
        Long userId = UserContext.require();
        String question = (String) request.get("question");
        String userAnswer = (String) request.get("userAnswer");
        String expectedAnswer = (String) request.getOrDefault("expectedAnswer", "");
        @SuppressWarnings("unchecked")
        List<String> followUpQAs = (List<String>) request.getOrDefault("followUpQAs", List.of());

        InterviewService.InterviewFeedback fb = interviewService.generateFeedback(
                userId, question, userAnswer, expectedAnswer, followUpQAs);
        return ResponseEntity.ok(fb);
    }
}
