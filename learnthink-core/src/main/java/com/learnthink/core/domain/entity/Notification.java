package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 消息通知实体
 */
@Data
@TableName("notifications")
public class Notification {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("user_id")
    private String userId;

    private String type;

    private String title;

    private String message;

    @TableField("is_read")
    private Boolean isRead;

    @TableField("is_pushed")
    private Boolean isPushed;

    @TableField("pushed_at")
    private LocalDateTime pushedAt;

    @TableField("ref_id")
    private String refId;

    @TableField("ref_type")
    private String refType;

    @TableField("payload_json")
    private String payloadJson;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
