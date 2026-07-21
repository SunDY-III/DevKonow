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
     * 服务端推送：question / result / passed / failed
     */
    @GetMapping(value = "/session", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter startSession(
            @RequestParam String conversationId,
            @RequestParam String question,
            @RequestParam String answer,
            @RequestParam(required = false) String sourceChunks) {
        UserContext.require();
        SseEmitter emitter = new SseEmitter(300_000L);

        CompletableFuture.runAsync(() -> {


            try {
                // 生成追问
                String verifyQuestion = feynmanService.generateVerifyQuestion(
                        conversationId, question, answer,
                        sourceChunks != null ? java.util.List.of(sourceChunks.split(",")) : java.util.List.of());

                // 初始化会话
                FeynmanSession session = new FeynmanSession();
                session.setConversationId(conversationId);
                session.setUserId(UserContext.get());
                session.setQuestion(question);
                session.setOriginalAnswer(answer);
                session.setStatus("questioning");
                feynmanService.saveSession(session);

                emitter.send(SseEmitter.event().name("question").data(
                        Map.of("question", verifyQuestion, "round", 1)));

            } catch (Exception e) {
                try { emitter.send(SseEmitter.event().name("error").data(Map.of("message", e.getMessage()))); }
                catch (Exception ignored) {}
            }
        });

        emitter.onCompletion(() -> {});
        emitter.onTimeout(() -> {});
        return emitter;
    }

    /**
     * 提交 Feynman 回答。
     */
    @PostMapping("/answer")
    public ResponseEntity<Map<String, Object>> submitAnswer(@RequestBody Map<String, String> request) {
        String conversationId = request.get("conversationId");
        String userAnswer = request.get("answer");

        var sessionOpt = feynmanService.loadSession(conversationId);
        if (sessionOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "会话不存在或已过期"));
        }

        FeynmanSession session = sessionOpt.get();
        int round = session.getRounds().size() + 1;

        // 评判
        var judgment = feynmanService.judge(conversationId,
                session.getRounds().isEmpty() ? "" : session.getRounds().get(session.getRounds().size() - 1).getVerifyQuestion(),
                userAnswer, session.getOriginalAnswer(), round, session.getCorrectCount());

        // 记录本轮
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

        // 判断是否通过
        boolean passed = session.getCorrectCount() >= 2;
        boolean failed = session.getFailedCount() >= 2 || round >= 3;

        if (passed) {
            session.setStatus("passed");
            session.setPassed(true);
        } else if (failed) {
            session.setStatus("failed");
            session.setFailed(true);
        } else {
            session.setStatus("questioning");
        }

        feynmanService.saveSession(session);

        return ResponseEntity.ok(Map.of(
                "correct", judgment.isCorrect(),
                "feedback", judgment.getFeedback(),
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
