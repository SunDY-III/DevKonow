package com.devknow.controller;

import com.devknow.common.UserContext;
import com.devknow.project.ImpactAnalysisService;
import com.devknow.project.ImpactReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequestMapping("/api/project")
@RequiredArgsConstructor
public class ImpactController {

    private final ImpactAnalysisService impactService;
    private final ExecutorService impactExecutor;

    @GetMapping(value = "/{projectId}/impact/{commitHash}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeImpact(@PathVariable Long projectId, @PathVariable String commitHash) {
        UserContext.require();
        SseEmitter emitter = new SseEmitter(300_000L);
        AtomicBoolean completed = new AtomicBoolean(false);

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                ImpactReport report = impactService.analyzeCommit(projectId, commitHash, msg -> {
                    try {
                        emitter.send(SseEmitter.event().name("progress").data(Map.of("message", msg)));
                    } catch (Exception ignored) {}
                });

                if (!completed.get()) {
                    emitter.send(SseEmitter.event().name("result").data(Map.of(
                            "success", report.isSuccess(),
                            "error", report.getError(),
                            "commitHash", report.getCommitHash(),
                            "commitMessage", report.getCommitMessage(),
                            "changedFiles", report.getChangedFiles() != null
                                    ? report.getChangedFiles() : java.util.List.of(),
                            "llmReport", report.getLlmReport() != null
                                    ? report.getLlmReport() : ""
                    )));
                    completed.set(true);
                    emitter.complete();
                }
            } catch (Exception e) {
                if (!completed.get()) {
                    completed.set(true);
                    try {
                        emitter.send(SseEmitter.event().name("error").data(Map.of("error", e.getMessage())));
                    } catch (Exception ignored) {}
                    emitter.complete();
                }
            }
        }, impactExecutor);

        emitter.onTimeout(() -> {
            completed.set(true);
            future.cancel(true);
        });
        emitter.onCompletion(() -> {
            completed.set(true);
            if (!future.isDone()) future.cancel(true);
        });

        return emitter;
    }

    @GetMapping("/{projectId}/impact/{commitHash}/json")
    public ResponseEntity<ImpactReport> analyzeImpactJson(@PathVariable Long projectId,
                                                           @PathVariable String commitHash) {
        UserContext.require();
        ImpactReport report = impactService.analyzeCommit(projectId, commitHash, msg -> {});
        return ResponseEntity.ok(report);
    }
}
