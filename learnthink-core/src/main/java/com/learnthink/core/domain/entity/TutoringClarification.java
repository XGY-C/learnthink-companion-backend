package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("tutoring_clarifications")
public class TutoringClarification {
    @TableId
    private String id;
    private String sessionId;
    private Integer round;
    private String reactState;
    private String clarificationJson;
    private String studentResponse;
    private LocalDateTime createdAt;
    private LocalDateTime respondedAt;
}
