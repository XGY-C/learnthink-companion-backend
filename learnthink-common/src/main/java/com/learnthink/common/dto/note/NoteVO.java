package com.learnthink.common.dto.note;

import lombok.Data;

@Data
public class NoteVO {
    private String id;
    private String userId;
    private String courseId;
    private String notebookId;
    private String resourcePackId;
    private String resourceItemId;
    private String resourceTitle;
    private String sectionTitle;
    private String selectedText;
    private String anchorId;
    private String textRange;
    private String content;
    private String createdAt;
    private String updatedAt;
}
