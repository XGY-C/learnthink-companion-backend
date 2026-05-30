package com.learnthink.common.dto.plan;

import lombok.Data;

/**
 * 确认大计划请求 (v3.0)
 */
@Data
public class PlanConfirmRequest {
    // 课程ID
    private String courseId;
    // 用户画像版本号
    private Integer profileVersion;
    // 用户编辑后确认的完整大计划 JSON
    private String planJson;
    // 用户需求文本
    private String requirementText;
    // 关联的对话会话ID，用于任务关联和历史加载还原
    private String chatId;
}
