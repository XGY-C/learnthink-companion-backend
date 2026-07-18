package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 原子消息实体，取代 profile_chats.messages_json。
 * 对应表 chat_messages。
 */
@Data
@TableName("chat_messages")
public class ChatMessage {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String sessionId;
    private String userId;
    private Integer seqNum;          // 全局有序序列号（对应 chat_sessions.message_count）
    private Integer roundNum;        // 对话轮次（(seqNum + 1) / 2）
    private String role;             // user | assistant | system
    private String content;          // MEDIUMTEXT
    private String mode;             // chat | lecture | resource | plan
    private String feedback;         // like | dislike | null
    private String metadataJson;     // JSON — 按 mode 不同结构

    private LocalDateTime createdAt;
}
