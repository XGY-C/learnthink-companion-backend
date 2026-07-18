package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("forum_comment_likes")
public class ForumCommentLike {
    @TableId(type = IdType.INPUT)
    private String userId;
    private String commentId;
    private String action;
    private LocalDateTime createdAt;
}
