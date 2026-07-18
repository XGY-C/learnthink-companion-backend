package com.learnthink.common.dto.note;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateNoteRequest {
    @NotBlank(message = "课程ID不能为空")
    private String courseId;

    private String notebookId;

    private String resourcePackId;
    private String resourceItemId;
    private String resourceTitle;
    private String sectionTitle;

    @NotBlank(message = "笔记内容不能为空")
    private String content;

    private String selectedText;
    private String anchorId;
    private String textRange;
}
