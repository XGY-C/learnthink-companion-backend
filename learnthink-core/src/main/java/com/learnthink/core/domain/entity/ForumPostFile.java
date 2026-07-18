package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("forum_post_files")
public class ForumPostFile {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String postId;
    private String fileName;
    private String fileUrl;
    private Long fileSize;
    private String fileType;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
