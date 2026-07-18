package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("forum_favorites")
public class ForumFavorite {
    @TableId(type = IdType.INPUT)
    private String userId;
    private String postId;
    private LocalDateTime createdAt;
}
