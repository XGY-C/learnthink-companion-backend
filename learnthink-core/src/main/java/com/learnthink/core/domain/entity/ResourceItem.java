package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资源项（5类资源+视频脚本）
 */
@Data
@TableName("resource_items")
public class ResourceItem {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;

    private String courseId;

    private String packId;

    private String folderId;

    private String taskId;

    /**
    * doc/mindmap/quiz/reading/code/video
     */
    private String type;

    private String title;

    /**
     * pending/ready/failed
     */
    private String status;

    /**
     * 对象存储key
     */
    private String contentRef;

    /**
     * text/markdown, application/json
     */
    private String contentMime;

    /**
     * 难度、标签、估时、quality_score
     */
    private String metadataJson;

    /**
     * 证据列表（doc_id,title,chunk_id,quote,locator,relevance）
     */
    private String sourcesJson;

    /**
     * high/medium/low（与DB confidence varchar同步，不做decimal转换）
     */
    private String confidence;

    /**
     * 质量评分 0.00~100.00
     */
    private BigDecimal qualityScore;

    /**
     * approved/rejected/pending
     */
    private String reviewStatus;

    private String reviewSummary;

    /** Sub-topic index (0 = topic-level, 1..N = sub-topic) */
    private Integer subtopicIndex;

    private LocalDateTime deletedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
