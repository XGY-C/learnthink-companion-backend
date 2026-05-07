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
     * 8维画像数据
     */
    private String dimensionsJson;

    /**
     * 供Planner使用的压缩摘要
     */
    private String summaryJson;

    /**
     * 关联对话记录ID列表
     */
    private String sourceChatIds;

    private LocalDateTime createdAt;
}
