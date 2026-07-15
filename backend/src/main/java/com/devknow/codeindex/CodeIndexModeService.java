package com.devknow.codeindex;

import com.devknow.codeindex.scip.ScipIndexGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 代码索引模式运行时管理器。
 *
 * <p>支持运行时动态切换模式，无需重启应用。
 * 切换 SCIP 模式时自动触发索引生成，通过 SSE 推送进度。
 */
@Slf4j
@Service
public class CodeIndexModeService {

    private final AtomicReference<CodeIndexMode> currentMode;
    private final ScipIndexGenerator scipIndexGenerator;
    private final Map<String, SseEmitter> progressEmitters = new ConcurrentHashMap<>();

    /** 切换状态：null=空闲, "generating"=正在生成SCIP索引 */
    private final AtomicReference<String> switchingStatus = new AtomicReference<>(null);

    private String currentProjectDir = "";

    public CodeIndexModeService(@Value("${app.codeindex.mode:tree-sitter}") String defaultMode,
                                ScipIndexGenerator scipIndexGenerator) {
        this.currentMode = new AtomicReference<>(parseMode(defaultMode));
        this.scipIndexGenerator = scipIndexGenerator;
        log.info("CodeIndexMode 初始化: mode={}", this.currentMode.get());
    }

    private CodeIndexMode parseMode(String mode) {
        if ("scip".equalsIgnoreCase(mode)) return CodeIndexMode.SCIP;
        return CodeIndexMode.TREE_SITTER;
    }

    /** 获取当前模式 */
    public CodeIndexMode getCurrentMode() {
        return currentMode.get();
    }

    /** 获取切换状态 */
    public String getSwitchingStatus() {
        String s = switchingStatus.get();
        return s != null ? s : "idle";
    }

    /** 设置当前项目目录（用于 SCIP 索引生成） */
    public void setCurrentProjectDir(String projectDir) {
        this.currentProjectDir = projectDir;
    }

    /**
     * 运行时切换模式。
     * <p>
     * 切换到 tree-sitter：立即生效。
     * 切换到 scip：如果已有 index.scip 立即生效，
     * 否则异步生成索引，完成后自动切换。
     *
     * @param targetMode 目标模式
     * @param projectDir 项目根目录（SCIP 索引生成用）
     * @return 切换结果描述
     */
    public SwitchResult switchMode(CodeIndexMode targetMode, String projectDir) {
        if (targetMode == currentMode.get()) {
            return new SwitchResult(true, "已经是 " + targetMode + " 模式", false);
        }

        if (targetMode == CodeIndexMode.TREE_SITTER) {
            currentMode.set(CodeIndexMode.TREE_SITTER);
            switchingStatus.set(null);
            log.info("模式切换: tree-sitter（立即生效）");
            return new SwitchResult(true, "已切换为 Tree-sitter 模式", false);
        }

        // 切换到 SCIP
        if (projectDir != null) this.currentProjectDir = projectDir;

        // 检查是否已有索引文件
        if (scipIndexGenerator.hasIndexFile(currentProjectDir)) {
            currentMode.set(CodeIndexMode.SCIP);
            log.info("模式切换: scip（已有索引文件，立即生效）");
            return new SwitchResult(true, "已切换为 SCIP 模式", false);
        }

        // 需要生成索引，异步执行
        switchingStatus.set("generating");
        log.info("模式切换: scip（无索引文件，开始异步生成）");

        // 异步生成索引
        String projectDirFinal = currentProjectDir;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                boolean success = scipIndexGenerator.generateIndex(projectDirFinal, this::notifyProgress);
                if (success) {
                    currentMode.set(CodeIndexMode.SCIP);
                    switchingStatus.set(null);
                    notifyProgress("SCIP 索引生成完成，已自动切换模式");
                    log.info("SCIP 索引生成完成，已切换为 scip 模式");
                } else {
                    switchingStatus.set(null);
                    notifyProgress("SCIP 索引生成失败，模式未变更");
                    log.warn("SCIP 索引生成失败");
                }
            } catch (Exception e) {
                switchingStatus.set(null);
                notifyProgress("SCIP 索引生成异常: " + e.getMessage());
                log.error("SCIP 索引生成异常", e);
            }
        });

        return new SwitchResult(true, "正在生成 SCIP 索引...", true);
    }

    /** SSE 订阅进度 */
    public SseEmitter subscribeProgress() {
        SseEmitter emitter = new SseEmitter(300_000L); // 5分钟超时
        String key = "emitter-" + System.currentTimeMillis();
        progressEmitters.put(key, emitter);
        emitter.onCompletion(() -> progressEmitters.remove(key));
        emitter.onTimeout(() -> progressEmitters.remove(key));
        // 发送初始状态
        try {
            emitter.send(SseEmitter.event().name("status").data(Map.of(
                    "mode", currentMode.get().name(),
                    "switching", switchingStatus.get() != null,
                    "status", switchingStatus.get() != null ? switchingStatus.get() : "idle"
            )));
        } catch (IOException ignored) {}
        return emitter;
    }

    private void notifyProgress(String message) {
        Map<String, Object> data = Map.of(
                "message", message,
                "mode", currentMode.get().name(),
                "switching", switchingStatus.get() != null,
                "status", switchingStatus.get() != null ? switchingStatus.get() : "idle"
        );
        progressEmitters.values().removeIf(e -> {
            try {
                e.send(SseEmitter.event().name("progress").data(data));
                return false;
            } catch (IOException ex) {
                return true; // 移除断开的 emitter
            }
        });
    }

    /** 切换结果 */
    public record SwitchResult(boolean success, String message, boolean async) {}
}
