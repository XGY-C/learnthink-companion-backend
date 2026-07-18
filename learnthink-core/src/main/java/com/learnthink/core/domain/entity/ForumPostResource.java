package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("forum_post_resources")
public class ForumPostResource {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String postId;
    private String resourceItemId;
    private String resourceTitle;
    private String resourceType;
    private String resourceSummary;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
