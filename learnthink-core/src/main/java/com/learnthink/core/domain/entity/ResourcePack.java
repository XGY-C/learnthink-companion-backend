package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 资源包主表
 */
@Data
@TableName("resource_packs")
public class ResourcePack {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;

    private String courseId;

    private String topic;

    /**
     * profile_versions.id（UUID），非版本号数字
     */
    @TableField("generated_from_profile_version_id")
    private String generatedFromProfileVersionId;

    private String taskId;

    /**
     * 个性化推送原因标签
     */
    private String pushReasonJson;

    private LocalDateTime createdAt;
}
