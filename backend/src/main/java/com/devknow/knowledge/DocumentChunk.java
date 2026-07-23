package com.devknow.knowledge;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "document_chunk")
public class DocumentChunk {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long docId;
    private Integer docVersion;
    private Integer seq;
    private String content;

    /** Contextual Retrieval 上下文描述（LLM 生成，嵌入时使用） */
    @Column(name = "context_description", length = 500)
    private String contextDescription;
}
