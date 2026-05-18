package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 学习大计划指针表 (v3.0)
 */
@Data
@TableName("learning_plans")
public class LearningPlan {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;
    private String courseId;
    private Integer profileVersion;
    private Integer currentVersion;
    private String planJson;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
