package com.learnthink.common.dto.plan;

import lombok.Data;

/**
 * 预览大计划请求 (v3.0)
 */
@Data
public class PlanPreviewRequest {
    private String courseId;
    private Integer profileVersion;
    private String requirementText; // AI 分析总结的用户需求自然语言文本
    private String chatId;          // 关联的对话会话ID，预览即落库时使用
}
