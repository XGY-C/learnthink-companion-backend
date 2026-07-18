package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 旧版学习路径指针表（已弃用，被 LearningPlan 替代）
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
