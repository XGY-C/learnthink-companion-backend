package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("forum_reports")
public class ForumReport {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String reporterId;
    private String targetType;
    private String targetId;
    private String reason;
    private String status;
    private String handledBy;
    private LocalDateTime handledAt;
    private String handleNote;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
