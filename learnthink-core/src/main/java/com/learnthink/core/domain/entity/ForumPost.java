package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("forum_posts")
public class ForumPost {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String userId;
    private String courseId;
    private String title;
    private String summary;
    private String content;
    private String type;
    private Integer isPinned;
    private Integer isFeatured;
    private Integer viewCount;
    private Integer likeCount;
    private Integer dislikeCount;
    private Integer commentCount;
    private Integer favoriteCount;
    private Integer shareCount;
    private LocalDateTime lastActivityAt;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
