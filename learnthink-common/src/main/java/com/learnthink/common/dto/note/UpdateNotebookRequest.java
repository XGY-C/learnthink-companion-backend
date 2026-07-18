package com.learnthink.common.dto.note;

import lombok.Data;

@Data
public class UpdateNotebookRequest {
    private String name;
    private String description;
    private String cover;
    private Integer sortOrder;
    private Boolean isDefault;
}
