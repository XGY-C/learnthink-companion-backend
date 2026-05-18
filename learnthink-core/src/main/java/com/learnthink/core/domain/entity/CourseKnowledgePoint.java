package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 课程知识图谱节点（章节/概念树 + 前置依赖图）
 */
@Data
@TableName("course_knowledge_points")
public class CourseKnowledgePoint {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String courseId;

    private String parentId;

    private String name;

    private String kpType;    // course / chapter / section / concept / skill

    private String scope;     // core_curriculum / prerequisite / supplementary

    private Integer depth;

    private Integer sortOrder;

    private String description;

    private String learningObjectives;   // JSON array

    private Integer difficulty;

    private Integer estimatedMinutes;

    private String prerequisiteKps;      // JSON array of KP IDs

    private String relatedKps;           // JSON array of KP IDs

    private String keywords;             // JSON array

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
