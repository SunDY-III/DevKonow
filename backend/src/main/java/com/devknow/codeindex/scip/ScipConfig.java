package com.devknow.codeindex.scip;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SCIP 索引配置。
 *
 * <p>SCIP (SCIP Code Intelligence Protocol) 是 Sourcegraph 定义的
 * 跨语言代码索引协议。开启后系统会尝试读取项目目录下的 {@code index.scip} 文件，
 * 用其中的符号信息增强 Tree-sitter 解析结果（补充类型签名、跨文件调用链）。
 *
 * <p>默认关闭，因为 SCIP 需要外部 indexer 工具生成索引文件。
 * 开启方式：{@code app.codeindex.scip.enabled=true}
 */
@Component
public class ScipConfig {

    @Value("${app.codeindex.scip.enabled:false}")
    private boolean enabled;

    @Value("${app.codeindex.scip.index-file:index.scip}")
    private String indexFileName;

    public boolean isEnabled() {
        return enabled;
    }

    public String getIndexFileName() {
        return indexFileName;
    }
}
