package com.devknow.codeindex.scip;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * SCIP 索引生成器。
 *
 * <p>调用外部 SCIP indexer CLI 工具生成 {@code index.scip} 文件。
 * 支持的 indexer（按语言自动检测）：
 * <ul>
 *   <li>Java: {@code scip-java index}</li>
 *   <li>Go: {@code scip-go index}</li>
 *   <li>Python: {@code scip-python index}</li>
 *   <li>TypeScript: {@code scip-typescript index}</li>
 * </ul>
 *
 * <p>如果未安装对应 indexer，返回失败状态并提示用户安装。
 */
@Slf4j
@Component
public class ScipIndexGenerator {

    private static final String[] INDEXER_CANDIDATES = {
            "scip-java", "scip-go", "scip-python", "scip-typescript", "scip-cli"
    };

    /**
     * 检查项目目录下是否已有 index.scip。
     */
    public boolean hasIndexFile(String projectDir) {
        if (projectDir == null || projectDir.isEmpty()) return false;
        return Files.exists(Path.of(projectDir, "index.scip"));
    }

    /**
     * 为项目生成 SCIP 索引文件。
     *
     * @param projectDir   项目根目录
     * @param onProgress   进度回调
     * @return true=生成成功, false=失败
     */
    public boolean generateIndex(String projectDir, Consumer<String> onProgress) {
        if (projectDir == null || projectDir.isEmpty()) {
            onProgress.accept("项目目录为空");
            return false;
        }

        Path dir = Path.of(projectDir);
        if (!Files.isDirectory(dir)) {
            onProgress.accept("项目目录不存在: " + projectDir);
            return false;
        }

        // 检测可用的 SCIP indexer
        String indexer = detectIndexer(projectDir);
        if (indexer == null) {
            onProgress.accept("未检测到 SCIP indexer，请安装后重试。\n" +
                    "  Java: scip-java (https://sourcegraph.github.io/scip-java/)\n" +
                    "  Go: scip-go\n" +
                    "  Python: scip-python\n" +
                    "  TypeScript: scip-typescript");
            return false;
        }

        onProgress.accept("检测到 indexer: " + indexer);

        // 构建命令
        ProcessBuilder pb = switch (indexer) {
            case "scip-java" -> new ProcessBuilder("scip-java", "index")
                    .directory(dir.toFile());
            case "scip-go" -> new ProcessBuilder("scip-go", "index")
                    .directory(dir.toFile());
            case "scip-python" -> new ProcessBuilder("scip-python", "index")
                    .directory(dir.toFile());
            case "scip-typescript" -> new ProcessBuilder("scip-typescript", "index")
                    .directory(dir.toFile());
            default -> new ProcessBuilder("scip-cli", "index")
                    .directory(dir.toFile());
        };

        pb.redirectErrorStream(true);
        pb.environment().put("JAVA_OPTS", "-Xmx2g");  // Java indexer 需要更多内存

        try {
            onProgress.accept("正在运行: " + String.join(" ", pb.command()));
            log.info("SCIP 索引生成开始: dir={}, cmd={}", projectDir, pb.command());

            Process process = pb.start();

            // 读取输出流并推送进度
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    onProgress.accept(line);
                }
            }

            int exitCode = process.waitFor();
            log.info("SCIP 索引生成完成: exitCode={}", exitCode);

            if (exitCode == 0 && hasIndexFile(projectDir)) {
                onProgress.accept("索引文件已生成: index.scip");
                return true;
            } else {
                onProgress.accept("索引生成失败 (exit code: " + exitCode + ")");
                return false;
            }

        } catch (IOException e) {
            onProgress.accept("无法启动 SCIP indexer: " + e.getMessage());
            log.warn("SCIP indexer 启动失败: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            onProgress.accept("索引生成被中断");
            return false;
        }
    }

    /**
     * 检测项目适用哪种 SCIP indexer。
     * 优先检测已安装的 CLI，然后根据项目文件推断。
     */
    private String detectIndexer(String projectDir) {
        Path dir = Path.of(projectDir);

        // 1. 先检测已安装的 indexer
        for (String cmd : INDEXER_CANDIDATES) {
            if (isCommandAvailable(cmd)) {
                return cmd;
            }
        }

        // 2. 根据项目文件推断
        if (Files.exists(dir.resolve("pom.xml")) || Files.exists(dir.resolve("build.gradle"))
                || Files.exists(dir.resolve("build.gradle.kts"))) {
            return "scip-java";
        }
        if (Files.exists(dir.resolve("go.mod"))) {
            return "scip-go";
        }
        if (Files.exists(dir.resolve("setup.py")) || Files.exists(dir.resolve("pyproject.toml"))
                || Files.exists(dir.resolve("requirements.txt"))) {
            return "scip-python";
        }
        if (Files.exists(dir.resolve("package.json"))) {
            return "scip-typescript";
        }

        return null;
    }

    /** 检查系统命令是否可用 */
    private boolean isCommandAvailable(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    System.getProperty("os.name").toLowerCase().contains("win") ?
                            new String[]{"cmd", "/c", "where", cmd} :
                            new String[]{"which", cmd}
            ).redirectErrorStream(true);
            Process p = pb.start();
            int exit = p.waitFor();
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
