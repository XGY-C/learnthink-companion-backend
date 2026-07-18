package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 辅导章节实体，替代 TutoringAnswer。
 * 对应表 tutoring_sections。
 */
@Data
@TableName("tutoring_sections")
public class TutoringSection {
    @TableId
    private String id;
    private String tutoringSessionId;   // 原 sessionId
    private String parentSectionId;     // 原 parentAnswerId
    private String sectionId;
    private String title;               // 新增：章节标题
    private Integer sortOrder;          // 新增：章节排序
    private String content;
    private String diagrams;
    private Integer rating;             // 新增：学生评分 1-5
    private String feedback;            // 新增：学生反馈
    private LocalDateTime createdAt;
}
