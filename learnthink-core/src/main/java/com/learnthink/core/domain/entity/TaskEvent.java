package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 任务事件日志（可回放流水线）
 */
@Data
@TableName("task_events")
public class TaskEvent {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String taskId;

    /**
     * task.accepted/task.stage/resource.ready/review.flag/task.done
     */
    private String eventType;

    private String payloadJson;

    private LocalDateTime createdAt;
}
