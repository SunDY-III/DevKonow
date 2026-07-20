package com.devknow.todo;

import lombok.Data;
import java.util.Date;

/** TODO 实体。 */
@Data
public class TodoItem {
    private Long id;
    private Long projectId;
    private Long userId;
    private String title;
    private String description;
    private String status;     // pending / in_progress / completed
    private String difficulty; // beginner / intermediate / advanced
    private String chapter;
    private Integer sortOrder;
    private Date createdAt;
    private Date updatedAt;
}
