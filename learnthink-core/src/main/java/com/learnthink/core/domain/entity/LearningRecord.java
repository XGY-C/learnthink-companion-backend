package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("learning_records")
public class LearningRecord {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String userId;
    private String courseId;
    private String planId;
    private String moduleId;
    private String activityId;
    private String resourcePackId;
    private String resourceItemId;
    private String resourceType;
    private String status;
    private Integer durationSeconds;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
