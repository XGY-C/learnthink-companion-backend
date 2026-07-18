package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 统一会话实体，取代 ProfileChat。
 * 对应表 chat_sessions。
 */
@Data
@TableName("chat_sessions")
public class ChatSession {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;
    private String courseId;
    private String title;
    private String type;          // chat | lecture | resource | plan
    private String status;        // active | archived
    private String profileVersionId;

    /** 消息总数，用作 seq_num 原子生成器（对应数据库 message_count 列） */
    private Integer messageCount;

    /** 当前对话轮次（对应数据库 current_round 列） */
    private Integer currentRound;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
