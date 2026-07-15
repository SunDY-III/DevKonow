package com.devknow.project;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * 项目结构自动扫描器。
 *
 * <p>扫描本地 Git 仓库目录，自动检测：
 * <ul>
 *   <li>主语言（按文件后缀统计）</li>
 *   <li>构建工具（检测 pom.xml / build.gradle / go.mod 等）</li>
 *   <li>框架（扫描源码中的关键注解和导入）</li>
 *   <li>入口点（main 方法、@SpringBootApplication 等）</li>
 *   <li>模块结构（按顶层包/目录聚类）</li>
 * </ul>
 */
@Slf4j
@Component
public class StructureScanner {

    /** 构建工具检测文件 */
    private static final Map<String, String> BUILD_TOOL_FILES = Map.of(
            "pom.xml", "Maven",
            "build.gradle", "Gradle",
            "build.gradle.kts", "Gradle",
            "go.mod", "Go Mod",
            "package.json", "npm",
            "Cargo.toml", "Cargo",
            "requirements.txt", "pip",
            "pyproject.toml", "Poetry",
            "CMakeLists.txt", "CMake"
    );

    /** 后缀 → 语言名 */
    private static final Map<String, String> EXT_TO_LANG = buildExtToLang();

    private static Map<String, String> buildExtToLang() {
        Map<String, String> m = new HashMap<>();
        m.put("java", "Java"); m.put("kt", "Kotlin"); m.put("go", "Go");
        m.put("py", "Python"); m.put("js", "JavaScript"); m.put("ts", "TypeScript");
        m.put("jsx", "React JSX"); m.put("tsx", "React TSX"); m.put("rs", "Rust");
        m.put("c", "C"); m.put("cpp", "C++"); m.put("h", "C/C++ Header");
        m.put("cs", "C#"); m.put("rb", "Ruby"); m.put("php", "PHP");
        m.put("swift", "Swift"); m.put("scala", "Scala"); m.put("vue", "Vue");
        m.put("sql", "SQL"); m.put("xml", "XML");
        m.put("yaml", "YAML"); m.put("yml", "YAML"); m.put("json", "JSON");
        m.put("md", "Markdown"); m.put("sh", "Shell"); m.put("dockerfile", "Dockerfile");
        return Collections.unmodifiableMap(m);
    }

    /** 最大扫描文件数（防止超大型项目卡死） */
    private static final int MAX_SCAN_FILES = 50_000;

    /**
     * 扫描项目目录。
     *
     * @param repoPath 本地仓库路径
     * @return 项目结构信息
     */
    public ProjectStructure scan(Path repoPath) {
        File root = repoPath.toFile();
        if (!root.exists() || !root.isDirectory()) {
            log.warn("扫描路径不存在: {}", repoPath);
            return ProjectStructure.builder().totalFiles(0).build();
        }

        try {
            // 收集所有文件
            List<File> allFiles = new ArrayList<>();
            try (Stream<Path> paths = Files.walk(repoPath)) {
                paths.filter(Files::isRegularFile)
                     .limit(MAX_SCAN_FILES)
                     .forEach(p -> allFiles.add(p.toFile()));
            }

            // 跳过 .git 目录和其他隐藏目录
            List<FileInfo> files = new ArrayList<>();
            Map<String, Integer> langCounts = new HashMap<>();

            for (File f : allFiles) {
                String relPath = repoPath.relativize(f.toPath()).toString().replace('\\', '/');
                // 跳过 .git 和隐藏目录
                if (relPath.startsWith(".git/") || relPath.startsWith(".")) continue;
                // 跳过 node_modules、target 等构建产物
                if (relPath.contains("/node_modules/") || relPath.contains("/target/")
                        || relPath.contains("/dist/") || relPath.contains("/build/")
                        || relPath.contains("/.next/") || relPath.contains("/vendor/")
                        || relPath.contains("/__pycache__/")) continue;

                String name = f.getName();
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
                // dockerfile 特殊处理
                if (name.equalsIgnoreCase("dockerfile")) ext = "dockerfile";

                files.add(FileInfo.builder()
                        .path(relPath)
                        .fileName(name)
                        .extension(ext)
                        .sizeBytes(f.length())
                        .build());

                langCounts.merge(EXT_TO_LANG.getOrDefault(ext, ext), 1, Integer::sum);
            }

            // 确定主语言
            String mainLanguage = langCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("Unknown");

            // 检测构建工具
            String buildTool = detectBuildTool(root);

            // 检测框架
            String framework = detectFramework(files, buildTool, mainLanguage);

            // 查找入口点
            List<String> entryPoints = findEntryPoints(files, mainLanguage, root);

            // 检测模块结构
            List<ModuleInfo> modules = detectModules(files);

            log.info("项目扫描完成: {} 文件, 主语言={}, 构建工具={}, 框架={}",
                    files.size(), mainLanguage, buildTool, framework);

            return ProjectStructure.builder()
                    .mainLanguage(mainLanguage)
                    .buildTool(buildTool)
                    .framework(framework)
                    .entryPoints(entryPoints)
                    .modules(modules)
                    .files(files)
                    .totalFiles(files.size())
                    .languageCounts(langCounts)
                    .build();

        } catch (IOException e) {
            log.warn("项目扫描失败: {}", repoPath, e);
            return ProjectStructure.builder().totalFiles(0).build();
        }
    }

    private String detectBuildTool(File root) {
        // 检查项目根目录是否有构建工具特征文件
        for (Map.Entry<String, String> entry : BUILD_TOOL_FILES.entrySet()) {
            if (new File(root, entry.getKey()).exists()) {
                return entry.getValue();
            }
        }
        // 递归检查子目录（多模块项目可能在子模块中有 pom.xml）
        File[] subDirs = root.listFiles(File::isDirectory);
        if (subDirs != null) {
            for (File dir : subDirs) {
                if (!dir.isHidden() && !dir.getName().startsWith(".")) {
                    for (Map.Entry<String, String> entry : BUILD_TOOL_FILES.entrySet()) {
                        if (new File(dir, entry.getKey()).exists()) {
                            return entry.getValue();
                        }
                    }
                }
            }
        }
        return null;
    }

    private String detectFramework(List<FileInfo> files, String buildTool, String mainLanguage) {
        // 根据语言和构建工具推断框架
        if ("Maven".equals(buildTool) || "Gradle".equals(buildTool)) {
            // 检查是否有 Spring Boot 特征文件
            for (FileInfo f : files) {
                String name = f.getFileName().toLowerCase();
                if (name.contains("springbootapplication")
                        || name.contains("springapplication")) {
                    return "Spring Boot";
                }
                if (name.equals("pom.xml")) {
                    // 简单检测 pom.xml 中是否有 spring-boot-starter
                    try {
                        String content = new String(
                                Files.readAllBytes(Path.of(f.getPath())), StandardCharsets.UTF_8);
                        if (content.contains("spring-boot-starter")) {
                            return "Spring Boot";
                        }
                    } catch (IOException ignored) {}
                }
            }
            return "Java";
        }
        if ("Go Mod".equals(buildTool)) {
            // Go 项目常见框架
            for (FileInfo f : files) {
                if (f.getExtension().equals("go")) {
                    try {
                        String content = new String(
                                Files.readAllBytes(Path.of(f.getPath())), StandardCharsets.UTF_8);
                        if (content.contains("gin")) return "Gin";
                        if (content.contains("beego")) return "Beego";
                        if (content.contains("fiber")) return "Fiber";
                        if (content.contains("echo")) return "Echo";
                    } catch (IOException ignored) {}
                }
            }
            return "Go";
        }
        return null;
    }

    private List<String> findEntryPoints(List<FileInfo> files, String mainLanguage, File root) {
        List<String> entryPoints = new ArrayList<>();
        for (FileInfo f : files) {
            String fullPath = new File(root, f.getPath()).getAbsolutePath();
            try {
                String content = new String(Files.readAllBytes(Path.of(fullPath)), StandardCharsets.UTF_8);
                if ("Java".equals(mainLanguage) && content.contains("public static void main")) {
                    entryPoints.add(f.getPath() + " (main)");
                }
                if ("Java".equals(mainLanguage) && content.contains("@SpringBootApplication")) {
                    entryPoints.add(f.getPath() + " (@SpringBootApplication)");
                }
                if ("Go".equals(mainLanguage) && content.contains("func main()")) {
                    entryPoints.add(f.getPath() + " (main)");
                }
            } catch (IOException | OutOfMemoryError ignored) {}
        }
        return entryPoints;
    }

    private List<ModuleInfo> detectModules(List<FileInfo> files) {
        // 按顶层源码目录聚类（如 src/main/java/com/trade/controller → controller）
        Map<String, Integer> moduleCounts = new LinkedHashMap<>();

        for (FileInfo f : files) {
            String path = f.getPath();
            String[] parts = path.split("/");

            // 寻找 src/main/java、src/main/kotlin 或 cmd/ 等典型源码根目录后的第一级
            for (int i = 0; i < parts.length - 1; i++) {
                if (parts[i].equals("src") && i + 2 < parts.length
                        && (parts[i+1].equals("main") || parts[i+1].equals("test"))) {
                    // src/main/java/com/trade/controller/OrderController.java
                    // → 取 "src/main/java" 后的第三段（controller）
                    if (i + 4 < parts.length) {
                        String module = parts[i + 4];
                        moduleCounts.merge(module, 1, Integer::sum);
                    }
                    break;
                }
                // Go 项目: cmd/api/main.go → 取 "cmd" 之后的段
                if (parts[i].equals("cmd") && i + 1 < parts.length) {
                    moduleCounts.merge("cmd/" + parts[i + 1], 1, Integer::sum);
                    break;
                }
                // Python: project_name/module_name/__init__.py
                if (parts[i].endsWith(".py") && i > 0) {
                    // 简单处理：取上一级目录名
                }
            }
        }

        // 只保留文件数 >= 3 的模块，按文件数降序排列
        return moduleCounts.entrySet().stream()
                .filter(e -> e.getValue() >= 3)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)  // 最多 10 个模块
                .map(e -> ModuleInfo.builder()
                        .name(e.getKey())
                        .fileCount(e.getValue())
                        .build())
                .toList();
    }
}
