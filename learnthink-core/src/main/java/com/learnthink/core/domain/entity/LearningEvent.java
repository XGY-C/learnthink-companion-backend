package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 学习行为事件
 */
@Data
@TableName("learning_events")
public class LearningEvent {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;

    /**
     * 可为空：非课程特定事件（如登录）不关联课程
     */
    private String courseId;

    /**
     * resource_opened/quiz_submitted/node_completed/resource_shared
     */
    private String eventType;

    private String payloadJson;

    private LocalDateTime createdAt;
}
