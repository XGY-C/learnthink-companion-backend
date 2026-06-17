package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 画像版本快照
 */
@Data
@TableName("profile_versions")
public class ProfileVersion {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;

    private String courseId;

    private Integer version;

    /**
     * 关联对话记录ID列表
     */
    private String sourceChatIds;

    /**
     * SSOT：核心画像 Markdown
     */
    private String coreProfileMd;

    /**
     * SSOT：学习风格画像 Markdown
     */
    private String learningProfileMd;

    /**
     * SSOT：知识掌握画像 Markdown
     */
    private String knowledgeProfileMd;

    /**
     * 前端展示 JSON（LLM Step 2 同步输出）
     */
    private String displayJson;

    private LocalDateTime createdAt;
}
