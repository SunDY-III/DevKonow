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

@Slf4j
@Component
public class StructureScanner {

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

    private static final int MAX_SCAN_FILES = 50_000;

    public ProjectStructure scan(Path repoPath) {
        File root = repoPath.toFile();
        if (!root.exists() || !root.isDirectory()) {
            log.warn("扫描路径不存在: {}", repoPath);
            return ProjectStructure.builder().totalFiles(0).build();
        }

        try {
            List<File> allFiles = new ArrayList<>();
            try (Stream<Path> paths = Files.walk(repoPath)) {
                paths.filter(Files::isRegularFile)
                     .limit(MAX_SCAN_FILES)
                     .forEach(p -> allFiles.add(p.toFile()));
            }

            List<FileInfo> files = new ArrayList<>();
            Map<String, Integer> langCounts = new HashMap<>();

            for (File f : allFiles) {
                String relPath = repoPath.relativize(f.toPath()).toString().replace('\\', '/');
                if (relPath.startsWith(".git/") || relPath.startsWith(".")) continue;
                if (relPath.contains("/node_modules/") || relPath.contains("/target/")
                        || relPath.contains("/dist/") || relPath.contains("/build/")
                        || relPath.contains("/.next/") || relPath.contains("/vendor/")
                        || relPath.contains("/__pycache__/")) continue;

                String name = f.getName();
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
                if (name.equalsIgnoreCase("dockerfile")) ext = "dockerfile";

                files.add(FileInfo.builder()
                        .path(relPath).fileName(name).extension(ext).sizeBytes(f.length())
                        .build());

                langCounts.merge(EXT_TO_LANG.getOrDefault(ext, ext), 1, Integer::sum);
            }

            String mainLanguage = langCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("Unknown");

            String buildTool = detectBuildTool(root);
            String framework = detectFramework(files, buildTool, mainLanguage);
            List<String> entryPoints = findEntryPoints(files, mainLanguage, root);
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
        for (Map.Entry<String, String> entry : BUILD_TOOL_FILES.entrySet()) {
            if (new File(root, entry.getKey()).exists()) {
                return entry.getValue();
            }
        }
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
        if ("Maven".equals(buildTool) || "Gradle".equals(buildTool)) {
            for (FileInfo f : files) {
                String name = f.getFileName().toLowerCase();
                if (name.contains("springbootapplication") || name.contains("springapplication")) {
                    return "Spring Boot";
                }
                if (name.equals("pom.xml")) {
                    try {
                        String content = new String(
                                Files.readAllBytes(Path.of(f.getPath())), StandardCharsets.UTF_8);
                        if (content.contains("spring-boot-starter")) return "Spring Boot";
                    } catch (IOException ignored) {}
                }
            }
            return "Java";
        }
        if ("Go Mod".equals(buildTool)) {
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
        // 无构建文件时的降级检测：按语言后缀推断框架
        if (buildTool == null) {
            if ("Java".equals(mainLanguage)) {
                for (FileInfo f : files) {
                    if (f.getFileName().toLowerCase().contains("springbootapplication")
                            || f.getFileName().toLowerCase().contains("springapplication")) {
                        return "Spring Boot";
                    }
                }
                return "Java";
            }
            if ("Python".equals(mainLanguage)) return "Python";
            if ("JavaScript".equals(mainLanguage) || "TypeScript".equals(mainLanguage)) return "Node.js";
            if ("Go".equals(mainLanguage)) return "Go";
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
        Map<String, Integer> moduleCounts = new LinkedHashMap<>();
        for (FileInfo f : files) {
            String path = f.getPath();
            String[] parts = path.split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                if (parts[i].equals("src") && i + 2 < parts.length
                        && (parts[i+1].equals("main") || parts[i+1].equals("test"))) {
                    if (i + 4 < parts.length) {
                        String module = parts[i + 4];
                        moduleCounts.merge(module, 1, Integer::sum);
                    }
                    break;
                }
                if (parts[i].equals("cmd") && i + 1 < parts.length) {
                    moduleCounts.merge("cmd/" + parts[i + 1], 1, Integer::sum);
                    break;
                }
            }
        }
        return moduleCounts.entrySet().stream()
                .filter(e -> e.getValue() >= 3)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> ModuleInfo.builder().name(e.getKey()).fileCount(e.getValue()).build())
                .toList();
    }
}
