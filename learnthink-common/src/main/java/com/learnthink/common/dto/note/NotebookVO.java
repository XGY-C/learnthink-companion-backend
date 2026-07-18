package com.learnthink.common.dto.note;

import lombok.Data;

@Data
public class NotebookVO {
    private String id;
    private String courseId;
    private String name;
    private String description;
    private String cover;
    private Integer sortOrder;
    private Boolean isDefault;
    private String createdAt;
    private String updatedAt;
}
