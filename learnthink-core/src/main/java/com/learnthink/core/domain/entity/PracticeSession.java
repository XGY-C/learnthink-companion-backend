package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("practice_sessions")
public class PracticeSession {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;
    private String courseId;
    private String sessionType;
    private String kpFilterJson;
    private String difficultyFilter;

    private Integer questionCount;
    private Integer correctCount;
    private Integer totalDurationSeconds;
    private Boolean completed;

    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    private String profileVersionId;
    private String weakKpsJson;
    private Integer aiGeneratedCount;
    private String evaluation;

    private LocalDateTime updatedAt;
}
