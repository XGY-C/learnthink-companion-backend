package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 路径主表（当前指针，v1.x 遗留）
 */
@Data
@TableName("learning_paths")
public class LearningPath {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;

    private String courseId;

    private Integer currentVersion;

    private LocalDateTime deletedAt;

    private LocalDateTime updatedAt;
}
