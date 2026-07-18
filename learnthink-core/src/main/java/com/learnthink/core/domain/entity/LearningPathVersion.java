package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 旧版学习路径版本快照表（已弃用，被 LearningPlanVersion 替代）
 */
@Data
@TableName("learning_path_versions")
public class LearningPathVersion {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String userId;
    private String courseId;
    private Integer version;

    @TableField("generated_from_profile_version_id")
    private String generatedFromProfileVersionId;

    private String pathJson;
    private LocalDateTime createdAt;
}
