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

    private String userId;

    private String courseId;

    /**
     * resource_generate / path_generate
     */
    private String taskType;

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

    private String stage;

    private Integer percent;

    private String errorCode;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
