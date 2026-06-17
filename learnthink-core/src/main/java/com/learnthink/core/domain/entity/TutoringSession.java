package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("tutoring_sessions")
public class TutoringSession {
    @TableId
    private String id;
    private String userId;
    private String chatId;
    private String courseId;
    private String question;
    private String executionPlan;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
