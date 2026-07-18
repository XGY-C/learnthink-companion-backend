package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("forum_follows")
public class ForumFollow {
    @TableId(type = IdType.INPUT)
    private String followerId;
    private String followeeId;
    private LocalDateTime createdAt;
}
