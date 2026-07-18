package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("notes")
public class Note {
    @TableId(type = IdType.ASSIGN_UUID)
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
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
