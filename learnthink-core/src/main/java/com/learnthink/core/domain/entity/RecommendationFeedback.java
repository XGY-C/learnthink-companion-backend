package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 推荐效果追踪实体
 */
@Data
@TableName("recommendation_feedback")
public class RecommendationFeedback {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("user_id")
    private String userId;

    @TableField("course_id")
    private String courseId;

    @TableField("pack_id")
    private String packId;

    private String action;

    private String source;

    @TableField("notification_id")
    private String notificationId;

    @TableField("score_json")
    private String scoreJson;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
