package com.learnthink.common.dto.plan;

import lombok.Data;

/**
 * 生成学习计划请求 (v3.0)
 */
@Data
public class PlanGenerateRequest {
    private String courseId;
    private Integer profileVersion;
    private boolean force; // 强制重新生成（取消已有进行中任务）
    private String requirementText; // AI 分析总结的用户需求自然语言文本
}
