package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("forum_post_likes")
public class ForumPostLike {
    @TableId(type = IdType.INPUT)
    private String userId;
    private String postId;
    private String action;
    private LocalDateTime createdAt;
}
