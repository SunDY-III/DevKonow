package com.devknow.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义切分 + 滑动窗口重叠 + Markdown 标题级分块。
 *
 * 策略（面试点）：
 * 1. Markdown 文档按标题层级切（# → ## → ###），每块保留标题行，结构完整；
 * 2. 非 Markdown / 普通文本回退到段落聚合 + overlap 窗口滑动；
 * 3. 单段超长再退化为定长硬切。
 */
@Component
public class TextSplitter {

    /** Markdown 标题正则：匹配 # ## ### #### 等 */
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,4})\\s+(.+)$", Pattern.MULTILINE);

    @Value("${app.rag.chunk-size}")    private int chunkSize;
    @Value("${app.rag.chunk-overlap}") private int overlap;

    /**
     * 按段落切分（默认，兼容旧逻辑）。
     */
    public List<String> split(String text) {
        return splitMarkdown(text);
    }

    /**
     * 使用指定 chunkSize 和 overlap 切分（供策略路由调用）。
     *
     * <p>当场景策略配置了不同的 chunkSize/overlap 时（如学习研读场景使用 1024/128），
     * 调用此方法而非使用组件默认值。
     *
     * @param text     待切分文本
     * @param chunkSize 目标块大小（字符数）
     * @param overlap   滑动窗口重叠（字符数）
     * @return 切分后的文本块列表
     */
    public List<String> splitWithParams(String text, int chunkSize, int overlap) {
        return splitMarkdownWithParams(text, chunkSize, overlap);
    }

    /**
     * 按 Markdown 标题层级切分（使用组件默认的 chunkSize / overlap）。
     */
    public List<String> splitMarkdown(String text) {
        return splitMarkdownWithParams(text, this.chunkSize, this.overlap);
    }

    /**
     * 按 Markdown 标题层级切分（使用指定参数）。
     * 每个标题及其下级内容作为一个 Chunk，保留标题层级结构。
     * 非 Markdown 文本自然降级到段落切分。
     */
    private List<String> splitMarkdownWithParams(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        // 检测是否是 Markdown（有标题标记）
        if (!HEADING_PATTERN.matcher(text).find()) {
            // 不是 Markdown → 按段落切
            return splitByParagraphWithParams(text, chunkSize, overlap);
        }

        // 按标题位置拆分成块
        Matcher matcher = HEADING_PATTERN.matcher(text);
        int lastStart = 0;
        String lastHeading = "";

        while (matcher.find()) {
            // 封上一块
            if (matcher.start() > 0) {
                String block = text.substring(lastStart, matcher.start()).strip();
                if (!block.isEmpty()) {
                    // 把标题放在块开头
                    chunks.add(lastHeading + "\n" + block);
                }
            }
            lastStart = matcher.start();
            lastHeading = matcher.group().strip();
        }

        // 最后一块
        if (lastStart < text.length()) {
            String block = text.substring(lastStart).strip();
            if (!block.isEmpty()) {
                chunks.add(block);
            }
        }

        // 如果分块太少或分块太大，回退到段落切分
        if (chunks.size() <= 1) {
            return splitByParagraphWithParams(text, chunkSize, overlap);
        }

        // 对超大块做二次切分
        List<String> result = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk.length() > chunkSize * 2) {
                result.addAll(splitByParagraphWithParams(chunk, chunkSize, overlap));
            } else {
                result.add(chunk);
            }
        }

        return result;
    }

    /**
     * 按段落 + 滑动窗口切分（使用组件默认参数）。
     */
    private List<String> splitByParagraph(String text) {
        return splitByParagraphWithParams(text, this.chunkSize, this.overlap);
    }

    /**
     * 按段落 + 滑动窗口切分（使用指定参数）。
     */
    private List<String> splitByParagraphWithParams(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\\n\\s*\\n|\\r\\n\\s*\\r\\n");
        StringBuilder buf = new StringBuilder();

        for (String p : paragraphs) {
            String para = p.strip();
            if (para.isEmpty()) continue;

            if (para.length() > chunkSize) {
                flush(chunks, buf);
                hardSplitWithParams(chunks, para, chunkSize, overlap);
            } else if (buf.length() + para.length() > chunkSize) {
                flush(chunks, buf);
                buf.append(tailOverlapWithParams(chunks, overlap)).append(para).append('\n');
            } else {
                buf.append(para).append('\n');
            }
        }
        flush(chunks, buf);
        return chunks;
    }

    private void hardSplit(List<String> chunks, String para) {
        hardSplitWithParams(chunks, para, this.chunkSize, this.overlap);
    }

    private void hardSplitWithParams(List<String> chunks, String para, int chunkSize, int overlap) {
        int step = chunkSize - overlap;
        for (int start = 0; start < para.length(); start += step) {
            chunks.add(para.substring(start, Math.min(start + chunkSize, para.length())));
            if (start + chunkSize >= para.length()) break;
        }
    }

    /** 取上一块尾部 overlap 字符拼到新块头部（使用默认参数） */
    private String tailOverlap(List<String> chunks) {
        return tailOverlapWithParams(chunks, this.overlap);
    }

    /** 取上一块尾部 overlap 字符拼到新块头部（使用指定参数） */
    private String tailOverlapWithParams(List<String> chunks, int overlap) {
        if (chunks.isEmpty() || overlap <= 0) return "";
        String prev = chunks.get(chunks.size() - 1);
        return prev.length() <= overlap ? prev : prev.substring(prev.length() - overlap);
    }

    private void flush(List<String> chunks, StringBuilder buf) {
        if (buf.length() > 0) {
            chunks.add(buf.toString().strip());
            buf.setLength(0);
        }
    }
}
