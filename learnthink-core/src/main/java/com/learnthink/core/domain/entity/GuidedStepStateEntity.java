package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("tutoring_guided_step")
public class GuidedStepStateEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String tutoringSessionId;
    private String stepId;
    private Integer stepOrder;
    private String stage;
    private String title;
    private String guidanceContent;
    private String question;
    private String studentAnswer;
    private String feedback;
    private String hint;
    private String evaluation;
    private Integer attemptCount;
    private Integer maxAttempts;
    private Long timeSpentMs;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
