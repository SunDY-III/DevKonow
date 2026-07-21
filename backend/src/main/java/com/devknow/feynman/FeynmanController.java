package com.devknow.feynman;

import com.devknow.common.UserContext;
import com.devknow.feynman.FeynmanSession.FeynmanRound;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Feynman 检验 REST API。
 *
 * <p>独立 SSE 端点，不污染通用 Chat 流。
 * 注意：UserContext ThreadLocal 不跨异步边界传播，必须在异步前捕获 userId。
 */
@Slf4j
@RestController
@RequestMapping("/api/feynman")
@RequiredArgsConstructor
public class FeynmanController {

    private final FeynmanService feynmanService;
    private final ExecutorService feynmanExecutor = Executors.newFixedThreadPool(4);

    @PreDestroy
    public void shutdown() {
        feynmanExecutor.shutdown();
    }

    /**
     * 开始 Feynman 检验（SSE）。
     */
    @GetMapping(value = "/session", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter startSession(
            @RequestParam String conversationId,
            @RequestParam String question,
            @RequestParam String answer,
            @RequestParam(required = false) String sourceChunks) {
        Long userId = UserContext.require(); // 异步前捕获
        SseEmitter emitter = new SseEmitter(300_000L);

        CompletableFuture.runAsync(() -> {
            try {
                String verifyQuestion = feynmanService.generateVerifyQuestion(
                        userId, conversationId, question, answer,
                        sourceChunks != null ? java.util.List.of(sourceChunks.split(",")) : java.util.List.of());

                FeynmanSession session = new FeynmanSession();
                session.setConversationId(conversationId);
                session.setUserId(userId);
                session.setQuestion(question);
                session.setOriginalAnswer(answer);
                session.setStatus("questioning");
                feynmanService.saveSession(session);

                emitter.send(SseEmitter.event().name("question").data(
                        Map.of("question", verifyQuestion, "round", 1)));
                emitter.complete();

            } catch (Exception e) {
                log.error("Feynman SSE 异常", e);
                try { emitter.send(SseEmitter.event().name("error").data(Map.of("message", e.getMessage()))); }
                catch (Exception ignored) {}
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        }, feynmanExecutor); // 使用专用线程池

        emitter.onCompletion(() -> {});
        emitter.onTimeout(() -> {});
        return emitter;
    }

    @PostMapping("/answer")
    public ResponseEntity<Map<String, Object>> submitAnswer(@RequestBody Map<String, String> request) {
        String conversationId = request.get("conversationId");
        String userAnswer = request.get("answer");
        if (conversationId == null || userAnswer == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "conversationId and answer are required"));
        }

        var sessionOpt = feynmanService.loadSession(conversationId);
        if (sessionOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "会话不存在或已过期"));
        }

        FeynmanSession session = sessionOpt.get();
        int round = session.getRounds().size() + 1;

        String lastQuestion = session.getRounds().isEmpty()
                ? "" : session.getRounds().get(session.getRounds().size() - 1).getVerifyQuestion();

        var judgment = feynmanService.judge(conversationId,
                lastQuestion, userAnswer, session.getOriginalAnswer(),
                round, session.getCorrectCount());

        FeynmanRound fr = new FeynmanRound();
        fr.setRoundNum(round);
        fr.setUserAnswer(userAnswer);
        fr.setCorrect(judgment.isCorrect());
        fr.setJudgment(judgment.getFeedback());
        fr.setHint(judgment.getHint());
        fr.setGapAnalysis(judgment.getGapAnalysis());
        session.getRounds().add(fr);

        if (judgment.isCorrect()) {
            session.setCorrectCount(session.getCorrectCount() + 1);
        } else {
            session.setFailedCount(session.getFailedCount() + 1);
        }

        boolean passed = session.getCorrectCount() >= 2;
        boolean failed = session.getFailedCount() >= 2 || round >= 3;

        if (passed) { session.setStatus("passed"); session.setPassed(true); }
        else if (failed) { session.setStatus("failed"); session.setFailed(true); }
        else { session.setStatus("questioning"); }

        feynmanService.saveSession(session);

        return ResponseEntity.ok(Map.of(
                "correct", judgment.isCorrect(),
                "feedback", judgment.getFeedback() != null ? judgment.getFeedback() : "",
                "hint", judgment.getHint() != null ? judgment.getHint() : "",
                "gapAnalysis", judgment.getGapAnalysis() != null ? judgment.getGapAnalysis() : "",
                "passed", session.isPassed(),
                "failed", session.isFailed(),
                "correctCount", session.getCorrectCount(),
                "totalRounds", round,
                "roundsLeft", 3 - round
        ));
    }

    @PostMapping("/skip")
    public ResponseEntity<Map<String, Object>> skip(@RequestBody Map<String, String> request) {
        String conversationId = request.get("conversationId");
        if (conversationId == null) {
            return ResponseEntity.ok(Map.of("skipped", false));
        }
        var sessionOpt = feynmanService.loadSession(conversationId);
        if (sessionOpt.isPresent()) {
            FeynmanSession session = sessionOpt.get();
            session.setSkipped(true);
            session.setStatus("skipped");
            feynmanService.saveSession(session);
        }
        return ResponseEntity.ok(Map.of("skipped", true));
    }
}
