package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 画像对话记录
 */
@Data
@TableName("profile_chats")
public class ProfileChat {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;

    private String courseId;

    private String profileVersionId;

    /**
     * [{role, content, at}]
     */
    private String messagesJson;

    private LocalDateTime createdAt;
}
