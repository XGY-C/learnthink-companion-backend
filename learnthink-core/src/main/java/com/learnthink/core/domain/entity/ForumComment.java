package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("forum_comments")
public class ForumComment {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String postId;
    private String parentId;
    private String rootId;
    private String userId;
    private String content;
    private Integer likeCount;
    private Integer dislikeCount;
    private Integer replyCount;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
