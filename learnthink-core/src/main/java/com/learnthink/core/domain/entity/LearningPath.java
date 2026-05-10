package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 路径主表（当前指针）
 */
@Data
@TableName("learning_paths")
public class LearningPath {
    @TableId
    private String userId;

    private String courseId;

    private Integer currentVersion;

    private LocalDateTime updatedAt;
}
