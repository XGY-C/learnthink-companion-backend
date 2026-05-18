package com.learnthink.common.dto.plan;

import lombok.Data;

/**
 * 生成学习计划请求 (v3.0)
 */
@Data
public class PlanGenerateRequest {
    private String courseId;
    private Integer profileVersion;
}
