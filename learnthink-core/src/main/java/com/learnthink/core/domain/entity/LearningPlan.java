package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 学习大计划指针表 (v3.0)
 */
@Data
@TableName("learning_plans")
public class LearningPlan {
    // 主键
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    // 用户ID
    private String userId;
    // 课程ID
    private String courseId;
    //
    private Integer profileVersion;
    //
    private Integer currentVersion;
    // 计划JSON字符串
    private String planJson;
    // 状态
    private String status;
    // 关联的对话会话ID，用于历史加载还原
    private String chatId;
    // 创建时间
    private LocalDateTime createdAt;
    // 更新时间
    private LocalDateTime updatedAt;
}
