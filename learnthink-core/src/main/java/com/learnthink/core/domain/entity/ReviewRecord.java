package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 审校记录
 */
@Data
@TableName("review_records")
public class ReviewRecord {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String resourceItemId;

    /** 冗余字段，便于按包查询审校结果 */
    private String resourcePackId;

    private String taskId;

    /**
     * approved / rejected
     */
    private String result;

    /**
     * 结构化驳回原因（missing_sources, 不一致, 敏感内容）
     */
    private String reasonsJson;

    private BigDecimal citationCoverage;

    private LocalDateTime createdAt;
}
