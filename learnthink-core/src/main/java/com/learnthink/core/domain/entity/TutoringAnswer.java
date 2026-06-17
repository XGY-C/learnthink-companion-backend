package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("tutoring_answers")
public class TutoringAnswer {
    @TableId
    private String id;
    private String sessionId;
    private String parentAnswerId;
    private String sectionId;
    private String content;
    private String diagrams;
    private LocalDateTime createdAt;
}
