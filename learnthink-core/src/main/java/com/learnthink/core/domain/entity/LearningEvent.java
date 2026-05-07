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
     * resource_opened/quiz_submitted/node_completed
     */
    private String eventType;

    private String payloadJson;

    private LocalDateTime createdAt;
}
