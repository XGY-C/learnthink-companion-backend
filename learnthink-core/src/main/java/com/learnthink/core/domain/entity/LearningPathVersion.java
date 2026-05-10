package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 路径版本快照
 */
@Data
@TableName("learning_path_versions")
public class LearningPathVersion {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;

    private String courseId;

    private Integer version;

    private Integer generatedFromProfileVersion;

    /**
     * nodes/edges/adjustments
     */
    private String pathJson;

    private LocalDateTime createdAt;
}
