package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("forum_post_tags")
public class ForumPostTag {
    @TableId(type = IdType.INPUT)
    private String postId;
    private String tagId;
    private LocalDateTime createdAt;
}
