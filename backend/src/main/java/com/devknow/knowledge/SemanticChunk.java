package com.devknow.knowledge;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 语义分块结果 —— 包含文本内容、层级上下文、可选的 LLM 描述。
 *
 * <p>与旧 {@link TextSplitter} 返回的纯字符串不同，SemanticChunk 保留了
 * 块在文档中的层级结构，支持 Contextual Retrieval 的上下文描述拼接。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticChunk {
    /** 块文本内容 */
    private String content;

    /** 最近的标题（如 "## 架构设计"） */
    private String heading;

    /** 标题层级（0=无标题, 1=#, 2=##, 3=###, 4=####） */
    private int headingLevel;

    /** 在文档中的序号 */
    private int seq;

    /** LLM 生成的上下文描述 —— 用于 Contextual Retrieval 嵌入拼接 */
    private String contextDescription;
}
