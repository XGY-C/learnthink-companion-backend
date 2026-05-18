package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户学习统计汇总实体
 */
@Data
@TableName("user_stats")
public class UserStats {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("user_id")
    private String userId;

    @TableField("course_id")
    private String courseId;

    @TableField("total_learning_minutes")
    private Integer totalLearningMinutes;

    @TableField("total_resource_packs")
    private Integer totalResourcePacks;

    @TableField("total_quiz_attempts")
    private Integer totalQuizAttempts;

    @TableField("total_quiz_score_avg")
    private BigDecimal totalQuizScoreAvg;

    @TableField("path_mastered_nodes")
    private Integer pathMasteredNodes;

    @TableField("path_total_nodes")
    private Integer pathTotalNodes;

    @TableField("current_weak_count")
    private Integer currentWeakCount;

    @TableField("prev_weak_count")
    private Integer prevWeakCount;

    @TableField("profile_version")
    private Integer profileVersion;

    @TableField("week_learning_minutes")
    private Integer weekLearningMinutes;

    @TableField("week_resource_packs")
    private Integer weekResourcePacks;

    @TableField("week_quiz_attempts")
    private Integer weekQuizAttempts;

    @TableField("weekly_activity_json")
    private String weeklyActivityJson;

    @TableField("calculated_at")
    private LocalDateTime calculatedAt;
}
