package com.devknow.vector;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorRecord {
    private Long docId;
    private Integer docVersion;
    private Long chunkId;     // document_chunk 主键，引用溯源用
    private Integer seq;      // 块序号
    private String fileName;
    private String content;
    private float[] vector;
}
