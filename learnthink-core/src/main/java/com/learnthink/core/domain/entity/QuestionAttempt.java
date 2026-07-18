package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("question_attempts")
public class QuestionAttempt {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;
    private String courseId;
    private String questionId;
    private String quizAttemptId;

    private String selectedAnswer;
    private Boolean isCorrect;
    private Integer durationSeconds;
    private Integer attemptNumber;

    private LocalDateTime createdAt;
}
