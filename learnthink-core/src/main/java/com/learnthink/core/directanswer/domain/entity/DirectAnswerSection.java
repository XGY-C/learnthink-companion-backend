package com.learnthink.core.directanswer.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 直接解答段落实体（占位）。
 * 对应表 direct_answer_sections。
 */
@Data
@TableName("direct_answer_sections")
public class DirectAnswerSection {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String sessionId;
    private String sectionId;
    private Integer sectionOrder;
    private String title;
    private String content;      // LONGTEXT — Markdown
    private String structured;   // JSON
    private String diagramSpec;  // JSON
    private LocalDateTime createdAt;
}
