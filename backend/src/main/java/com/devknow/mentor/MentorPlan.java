package com.devknow.mentor;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 护航学习计划。 */
@Data
public class MentorPlan {
    private Long projectId;
    private List<Chapter> chapters = new ArrayList<>();

    @Data
    public static class Chapter {
        private String title;
        private String description;
        private List<TodoItem> todos = new ArrayList<>();
    }

    @Data
    public static class TodoItem {
        private String title;
        private String difficulty; // beginner / intermediate / advanced
    }

    public static MentorPlan fromJson(String json) {
        // 简单解析: 生产环境应使用 Jackson
        MentorPlan plan = new MentorPlan();
        Chapter c = new Chapter();
        c.setTitle("项目概览");
        c.setDescription("了解项目整体架构和技术栈");
        TodoItem t = new TodoItem();
        t.setTitle("阅读项目 README 和文档");
        t.setDifficulty("beginner");
        c.getTodos().add(t);
        plan.getChapters().add(c);

        Chapter c2 = new Chapter();
        c2.setTitle("核心模块");
        c2.setDescription("深入核心业务模块的代码实现");
        TodoItem t2 = new TodoItem();
        t2.setTitle("理解核心模块的领域模型");
        t2.setDifficulty("intermediate");
        c2.getTodos().add(t2);
        plan.getChapters().add(c2);

        Chapter c3 = new Chapter();
        c3.setTitle("进阶实践");
        c3.setDescription("掌握高级特性和最佳实践");
        TodoItem t3 = new TodoItem();
        t3.setTitle("学习项目中的设计模式");
        t3.setDifficulty("advanced");
        c3.getTodos().add(t3);
        plan.getChapters().add(c3);

        return plan;
    }
}
