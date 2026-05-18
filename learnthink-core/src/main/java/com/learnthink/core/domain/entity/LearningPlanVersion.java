package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 大计划版本快照表 (v3.0)
 */
@Data
@TableName("learning_plan_versions")
public class LearningPlanVersion {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String planId;
    private String userId;
    private String courseId;
    private Integer version;

    @TableField("generated_from_profile_version_id")
    private String generatedFromProfileVersionId;

    private String planJson;
    private LocalDateTime createdAt;
}
