package com.devknow.codereview;

import com.devknow.common.BizException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 代码审查 Agent 工具集。
 *
 * <p>低置信路由回退时使用：搜不到具体方法 → Agent 逐文件扫描分析。
 */
@Slf4j
@Component
public class CodeTools {

    /** 每会话工具调用轮次上限 */
    private static final int MAX_ROUNDS = 4;

    /** 每会话去重集 */
    private final ThreadLocal<java.util.HashSet<String>> invoked = ThreadLocal.withInitial(java.util.HashSet::new);
    private final ThreadLocal<Integer> rounds = ThreadLocal.withInitial(() -> 0);

    public void beginSession() {
        invoked.get().clear();
        rounds.set(0);
    }

    public void endSession() {
        invoked.remove();
        rounds.remove();
    }

    private void guard(String toolName, String argsFingerprint) {
        if (rounds.get() >= MAX_ROUNDS) {
            throw new BizException("已达到最大扫描轮次(" + MAX_ROUNDS + ")");
        }
        rounds.set(rounds.get() + 1);
        String key = toolName + "|" + argsFingerprint;
        if (!invoked.get().add(key)) {
            throw new BizException("重复的扫描请求，已跳过: " + toolName);
        }
    }

    @Tool("读取指定源码文件的完整内容。返回文件的全部源码和文件路径。")
    public String scanFile(@P("文件完整路径") String filePath) {
        guard("scanFile", filePath);
        try {
            Path path = Paths.get(filePath);
            if (!path.toFile().exists()) {
                return "文件不存在: " + filePath;
            }
            // 跳过过大文件
            if (path.toFile().length() > 500_000) {
                return "文件过大，跳过: " + filePath + " (" + path.toFile().length() + " bytes)";
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            log.info("[agent] scanFile: {} ({} chars)", filePath, content.length());
            return "=== " + filePath + " ===\n" + content;
        } catch (Exception e) {
            return "读取失败: " + filePath + " (" + e.getMessage() + ")";
        }
    }

    @Tool("从项目文件中搜索关键词，返回匹配的文件路径列表。")
    public String grepFiles(@P("项目根目录路径") String projectRoot,
                             @P("搜索关键词") String keyword) {
        guard("grepFiles", keyword);
        StringBuilder result = new StringBuilder();
        try {
            Path root = Paths.get(projectRoot);
            if (!root.toFile().exists()) return "项目目录不存在: " + projectRoot;

            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                      .filter(f -> f.getFileName().toString().matches(".*\\.(java|kt|go|py|js|ts)$"))
                      .limit(20)
                      .forEach(f -> {
                          try {
                              String content = Files.readString(f);
                              if (content.contains(keyword)) {
                                  result.append(root.relativize(f)).append('\n');
                              }
                          } catch (Exception ignored) {}
                      });
            }
        } catch (Exception e) {
            return "搜索失败: " + e.getMessage();
        }
        return result.isEmpty() ? "未找到匹配文件" : result.toString();
    }
}
