package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 子计划表 (v3.0) — 每个 module 一条
 */
@Data
@TableName("sub_plans")
public class SubPlan {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String planId;
    private String moduleId;
    private Integer version;

    @TableField("sub_plan_json")
    private String subPlanJson;

    @TableField("generation_status")
    private String generationStatus;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
