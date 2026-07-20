package com.devknow.prompt;

import lombok.Data;

import java.util.Date;

/** Prompt 模板实体。 */
@Data
public class PromptTemplate {
    private Long id;
    private String type;       // teaching / mentoring / system / review
    private String name;       // 模板名称
    private String content;    // 模板内容
    private String variables;  // JSON 格式的变量列表
    private Integer version;
    private Date updatedAt;
}
