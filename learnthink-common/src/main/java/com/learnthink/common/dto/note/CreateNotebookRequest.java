package com.learnthink.common.dto.note;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateNotebookRequest {
    @NotBlank(message = "课程ID不能为空")
    private String courseId;

    @NotBlank(message = "笔记本名称不能为空")
    private String name;

    private String description;
    private String cover;
    private Integer sortOrder;
    private Boolean isDefault;
}
