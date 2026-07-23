package com.devknow.chat.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 长期记忆中的原子事实。
 *
 * <p>从对话历史中自动提取，用于跨轮次保持关键上下文。
 * 支持更新/修正：当用户推翻之前说法时，旧事实标记 superseded 并关联新事实。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryFact {
    /** 事实唯一 ID（content hash 或 UUID） */
    private String id;

    /** 事实文本 */
    private String text;

    /** 事实类别 */
    private FactCategory category;

    /** 所属会话 ID */
    private String conversationId;

    /** 创建时间戳（epoch ms） */
    private long createdAt;

    /** 最近更新时间戳 */
    private long updatedAt;

    /** 是否已被新事实取代 */
    @Builder.Default
    private boolean superseded = false;

    /** 取代此事实的新事实 ID */
    private String supersededById;
}
