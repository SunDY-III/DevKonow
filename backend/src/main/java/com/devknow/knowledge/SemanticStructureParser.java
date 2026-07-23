package com.devknow.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义结构解析器 —— 检测文档结构边界，生成语义完整的 Chunk。
 *
 * <p>与旧 {@link TextSplitter} 的定长/段落切分不同，此解析器：
 * <ul>
 *   <li>识别 Markdown 标题层级，按标题自然边界切分</li>
 *   <li>将代码块、表格视为不可分割的原子单元</li>
 *   <li>保留每个 Chunk 的标题上下文（heading + headingLevel）</li>
 *   <li>非结构化文本回退到段落聚合 + 定长硬切</li>
 * </ul>
 *
 * <p>设计原则：宁可多切（细粒度），不跨语义边界。后续 {@link ContextualDescriptionGenerator}
 * 会对每个 Chunk 生成上下文描述弥补粒度过细的问题。
 */
@Slf4j
@Component
public class SemanticStructureParser {

    // ==================== 正则 ====================

    /** Markdown 标题 */
    private static final Pattern HEADING_PATTERN =
            Pattern.compile("^(#{1,4})\\s+(.+)$", Pattern.MULTILINE);

    /** Markdown 代码块（围栏式 ``` 或 缩进式） */
    private static final Pattern FENCED_CODE_BLOCK =
            Pattern.compile("(?ms)^```.*?^```");

    /** Markdown 表格行 */
    private static final Pattern TABLE_ROW =
            Pattern.compile("^\\|.+\\|\\s*$", Pattern.MULTILINE);

    /** HTML 标签块 */
    private static final Pattern HTML_BLOCK_TAG =
            Pattern.compile("(?ms)<(div|table|pre|section|article|blockquote)[^>]*>.*?</\\1>");

    /** 连续空行（段落分隔） */
    private static final Pattern PARAGRAPH_BREAK =
            Pattern.compile("\\n\\s*\\n");

    // ==================== 公共入口 ====================

    /**
     * 解析文本为语义 Chunk 列表。
     *
     * @param text     原始文本
     * @param fileName 文件名（用于格式判断）
     * @return 语义 Chunk 列表，不会返回 null
     */
    public List<SemanticChunk> parse(String text, String fileName) {
        if (text == null || text.isBlank()) return List.of();

        // 判断文档类型
        DocumentType type = detectType(text, fileName);

        return switch (type) {
            case MARKDOWN -> parseMarkdown(text);
            case PLAIN_TEXT -> parsePlainText(text);
        };
    }

    /**
     * 兼容旧接口：仅传文本，自动检测类型。
     */
    public List<SemanticChunk> parse(String text) {
        return parse(text, null);
    }

    // ==================== 格式检测 ====================

    private enum DocumentType { MARKDOWN, PLAIN_TEXT }

    private DocumentType detectType(String text, String fileName) {
        // 按文件名后缀判断
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".md") || lower.endsWith(".markdown")
                    || lower.endsWith(".rst") || lower.endsWith(".txt")) {
                // .txt 也可能包含 Markdown，继续检测
                if (lower.endsWith(".txt")) {
                    return looksLikeMarkdown(text) ? DocumentType.MARKDOWN : DocumentType.PLAIN_TEXT;
                }
                return DocumentType.MARKDOWN;
            }
        }
        // 无文件名时靠内容判断
        return looksLikeMarkdown(text) ? DocumentType.MARKDOWN : DocumentType.PLAIN_TEXT;
    }

    /** 判断文本是否可能包含 Markdown 标记 */
    private boolean looksLikeMarkdown(String text) {
        if (text == null || text.length() < 20) return false;
        return HEADING_PATTERN.matcher(text).find()
                || text.contains("```")
                || text.contains("|");
    }

    // ==================== Markdown 解析 ====================

    /**
     * Markdown 解析策略：
     * 1. 先按标题切分大块
     * 2. 每个大块内按段落/代码块/表格进一步细分
     * 3. 保留标题层级信息
     */
    private List<SemanticChunk> parseMarkdown(String text) {
        // 1. 按标题切分大块
        List<HeadingBlock> headingBlocks = splitByHeading(text);
        if (headingBlocks.isEmpty()) {
            // 无标题时降级为普通文本解析
            return parsePlainText(text);
        }

        List<SemanticChunk> result = new ArrayList<>();
        int seq = 0;

        for (HeadingBlock block : headingBlocks) {
            // 2. 大块内进一步按代码块/表格/段落细分
            List<SemanticChunk> subChunks = splitBlockContent(block);
            for (SemanticChunk chunk : subChunks) {
                chunk.setSeq(seq++);
                chunk.setHeading(block.heading);
                chunk.setHeadingLevel(block.level);
                result.add(chunk);
            }
        }

        // 如果分块太少（只有1块），回退到段落切分
        if (result.size() <= 1) {
            return parsePlainText(text);
        }

        return result;
    }

    /**
     * 按标题位置切分，保留标题层级结构。
     * 返回 List<HeadingBlock>，其中每个 block 包含该标题及其下的所有内容。
     */
    private List<HeadingBlock> splitByHeading(String text) {
        List<HeadingBlock> blocks = new ArrayList<>();
        Matcher matcher = HEADING_PATTERN.matcher(text);
        int lastStart = 0;
        String lastHeading = "";
        int lastLevel = 0;

        while (matcher.find()) {
            // 封上一块
            if (matcher.start() > 0 && !lastHeading.isEmpty()) {
                String blockContent = text.substring(lastStart, matcher.start()).strip();
                if (!blockContent.isEmpty()) {
                    // 去掉块头部已包含的标题行（标题在 lastHeading 中已记录）
                    String body = blockContent.substring(lastHeading.length()).strip();
                    blocks.add(new HeadingBlock(lastHeading, lastLevel, body));
                }
            }
            lastStart = matcher.start();
            lastHeading = matcher.group().strip();
            lastLevel = matcher.group(1).length();  // # 的数量
        }

        // 最后一块
        if (lastStart < text.length()) {
            String blockContent = text.substring(lastStart).strip();
            if (!blockContent.isEmpty()) {
                String body = blockContent.substring(lastHeading.length()).strip();
                blocks.add(new HeadingBlock(lastHeading, lastLevel, body));
            }
        }

        return blocks;
    }

    /**
     * 在标题块内进一步按代码块/表格/段落切分。
     * 保证代码块和表格不被切断。
     */
    private List<SemanticChunk> splitBlockContent(HeadingBlock block) {
        String body = block.body;
        if (body == null || body.isBlank()) {
            // 标题后无正文，但标题本身也构成一个语义单元
            SemanticChunk chunk = new SemanticChunk();
            chunk.setContent(block.heading);
            return List.of(chunk);
        }

        // 1. 保护代码块（先提取出来，避免内部被切断）
        List<String> protectedBlocks = new ArrayList<>();
        String processed = protectAtomicUnits(body, protectedBlocks);

        // 2. 按段落分割
        String[] paragraphs = processed.split("\\n\\s*\\n");
        List<SemanticChunk> chunks = new ArrayList<>();

        for (String para : paragraphs) {
            String trimmed = para.strip();
            if (trimmed.isEmpty()) continue;

            // 恢复被保护的原子块
            trimmed = restoreProtectedUnits(trimmed, protectedBlocks);

            // 检测是否是表格
            if (isTable(trimmed)) {
                SemanticChunk chunk = new SemanticChunk();
                chunk.setContent(trimmed);
                chunks.add(chunk);
                continue;
            }

            // 检测是否是代码块
            if (trimmed.contains("```") || trimmed.startsWith("    ")) {
                SemanticChunk chunk = new SemanticChunk();
                chunk.setContent(trimmed);
                chunks.add(chunk);
                continue;
            }

            // 普通段落
            if (trimmed.length() > 50) {
                // 长段落 — 单独成块
                SemanticChunk chunk = new SemanticChunk();
                chunk.setContent(trimmed);
                chunks.add(chunk);
            } else {
                // 短段落 — 合并到上一个块
                if (!chunks.isEmpty()) {
                    SemanticChunk last = chunks.get(chunks.size() - 1);
                    // 避免过度合并导致块过大
                    if (last.getContent().length() + trimmed.length() < 2000) {
                        last.setContent(last.getContent() + "\n\n" + trimmed);
                        continue;
                    }
                }
                SemanticChunk chunk = new SemanticChunk();
                chunk.setContent(trimmed);
                chunks.add(chunk);
            }
        }

        return chunks;
    }

    /**
     * 保护代码块和 HTML 块，防止内部内容被段落切分切断。
     * 将受保护块替换为占位符，并在 protectedBlocks 中保存原内容。
     */
    private String protectAtomicUnits(String text, List<String> protectedBlocks) {
        String result = text;

        // 保护围栏式代码块
        Matcher codeMatcher = FENCED_CODE_BLOCK.matcher(result);
        result = protectMatches(result, codeMatcher, protectedBlocks);

        // 保护 HTML 块
        Matcher htmlMatcher = HTML_BLOCK_TAG.matcher(result);
        result = protectMatches(result, htmlMatcher, protectedBlocks);

        return result;
    }

    private String protectMatches(String text, Matcher matcher, List<String> protectedBlocks) {
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String placeholder = "%%%PROTECTED_" + protectedBlocks.size() + "%%%";
            protectedBlocks.add(matcher.group());
            matcher.appendReplacement(sb, placeholder);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String restoreProtectedUnits(String text, List<String> protectedBlocks) {
        String result = text;
        for (int i = 0; i < protectedBlocks.size(); i++) {
            result = result.replace("%%%PROTECTED_" + i + "%%%", protectedBlocks.get(i));
        }
        return result;
    }

    private boolean isTable(String text) {
        String[] lines = text.split("\n");
        if (lines.length < 2) return false;
        // 表格至少有标题行和分隔行
        return TABLE_ROW.matcher(lines[0]).matches()
                && lines[1].contains("---");
    }

    // ==================== 纯文本解析 ====================

    /**
     * 纯文本解析策略：
     * 1. 按空行切分段落
     * 2. 短段落合并（同一主题聚类）
     * 3. 长段落单独成块
     * 4. 超长段落硬切
     */
    private List<SemanticChunk> parsePlainText(String text) {
        List<SemanticChunk> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\\n\\s*\\n");

        StringBuilder buffer = new StringBuilder();
        int seq = 0;

        for (String p : paragraphs) {
            String para = p.strip();
            if (para.isEmpty()) continue;

            if (para.length() > 2000) {
                // 超长段落：刷出缓冲区，然后硬切
                flushBuffer(buffer, chunks, seq++);
                for (String segment : hardSplit(para, 1000, 100)) {
                    SemanticChunk chunk = new SemanticChunk();
                    chunk.setContent(segment);
                    chunk.setSeq(seq++);
                    chunks.add(chunk);
                }
            } else if (para.length() < 100 && buffer.length() > 0) {
                // 短段落：追加到缓冲区（合并聚类）
                buffer.append("\n\n").append(para);
            } else {
                // 新段落：刷出缓冲区，开始新块
                flushBuffer(buffer, chunks, seq++);
                buffer.append(para);
            }
        }
        flushBuffer(buffer, chunks, seq);

        return chunks;
    }

    private void flushBuffer(StringBuilder buf, List<SemanticChunk> chunks, int seq) {
        if (buf.length() > 0) {
            SemanticChunk chunk = new SemanticChunk();
            chunk.setContent(buf.toString().strip());
            chunk.setSeq(seq);
            chunks.add(chunk);
            buf.setLength(0);
        }
    }

    /** 定长硬切 */
    private List<String> hardSplit(String text, int chunkSize, int overlap) {
        List<String> result = new ArrayList<>();
        int step = chunkSize - overlap;
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(start + chunkSize, text.length());
            result.add(text.substring(start, end));
            if (end >= text.length()) break;
        }
        return result;
    }

    // ==================== 内部结构 ====================

    /** 标题块：一个标题及其下属内容 */
    private static class HeadingBlock {
        final String heading;
        final int level;
        final String body;

        HeadingBlock(String heading, int level, String body) {
            this.heading = heading;
            this.level = level;
            this.body = body;
        }
    }
}
