package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 做题记录
 */
@Data
@TableName("quiz_attempts")
public class QuizAttempt {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;

    private String courseId;

    private String topic;

    /**
     * 关联路径节点
     */
    private String nodeId;

    private String packId;

    /**
     * 用户作答
     */
    private String answersJson;

    private BigDecimal score;

    /**
     * 错误标签列表
     */
    private String weakTags;

    private Integer durationSeconds;

    private LocalDateTime createdAt;
}
