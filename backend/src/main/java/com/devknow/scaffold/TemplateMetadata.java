package com.devknow.scaffold;

import lombok.Data;

import java.util.List;

/** 模板元数据。 */
@Data
public class TemplateMetadata {
    private String id;
    private String name;
    private String description;
    private List<String> techStack;
    private List<String> tags;
    private String previewImage;
    private List<TemplateVariable> variables;

    @Data
    public static class TemplateVariable {
        private String key;
        private String label;
        private String defaultValue;
        private String description;
        private boolean required;
    }
}
