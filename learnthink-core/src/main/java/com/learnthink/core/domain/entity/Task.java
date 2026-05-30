package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 任务主表
 */
@Data
@TableName("tasks")
public class Task {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 课程ID
     */
    private String courseId;

    /**
     * resource_generate / path_generate
     */
    private String taskType;

    /**
     * 主题/话题
     */
    private String topic;

    /**
     * 请求的资源类型列表
     */
    private String requestedResourceTypes;

    /**
     * 生成时所依据的画像版本（profile_versions.id）
     */
    @TableField("profile_version_id")
    private String profileVersionId;

    /**
     * 关联的对话会话ID（NULL=非对话触发的任务）
     */
    @TableField("chat_id")
    private String chatId;

    /**
     * PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED
     */
    private String status;

    /**
     * 任务阶段
     */
    private String stage;

    /**
     * 完成百分比
     */
    private Integer percent;

    /**
     * 错误码
     */
    private String errorCode;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 开始时间
     */
    private LocalDateTime startedAt;

    /**
     * 完成时间
     */
    private LocalDateTime finishedAt;
}
