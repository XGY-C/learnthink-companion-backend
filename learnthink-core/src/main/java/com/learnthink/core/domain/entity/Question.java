package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("questions")
public class Question {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;
    private String courseId;
    private String sourceItemId;

    private String questionType;
    private Integer difficulty;
    private String title;
    private String optionsJson;
    private String answerJson;
    private String explanation;

    private String kpId;
    private String tagsJson;

    private Integer attemptCount;
    private Integer correctCount;

    private String status;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
