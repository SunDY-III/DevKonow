package com.zhishu.project;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模块信息模型（StructureScanner 扫描结果中的模块）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModuleInfo {
    private String name;        // 模块名，如 "controller"
    private String path;        // 相对路径，如 "src/main/java/com/trade/controller"
    private int fileCount;      // 该模块的文件数
}
