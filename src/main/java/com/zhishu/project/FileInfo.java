package com.zhishu.project;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件信息模型（StructureScanner 扫描结果中的单个文件）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileInfo {
    private String path;        // 相对路径，如 "src/main/java/com/trade/OrderService.java"
    private String fileName;    // 文件名，如 "OrderService.java"
    private String extension;   // 后缀，如 "java"
    private long sizeBytes;     // 文件大小
}
