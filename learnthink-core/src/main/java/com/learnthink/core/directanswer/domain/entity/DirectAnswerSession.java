package com.learnthink.core.directanswer.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 直接解答会话实体。
 * 对应表 direct_answer_sessions。
 */
@Data
@TableName("direct_answer_sessions")
public class DirectAnswerSession {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String userId;
    private String chatId;
    private String courseId;
    private String question;
    private String metadata;     // JSON — AnswerMetadata
    private String status;       // creating | generating | completed | failed
    @TableField("created_at")
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
