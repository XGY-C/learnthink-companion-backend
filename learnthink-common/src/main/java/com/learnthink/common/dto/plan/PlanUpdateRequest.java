package com.learnthink.common.dto.plan;

import lombok.Data;

/**
 * 更新 pending_decision 计划草稿请求 (v3.1)
 * 用户在 PlanEditor 中编辑模块后，实时保存到后端
 */
@Data
public class PlanUpdateRequest {
    /** 计划ID（首次保存时可为空，后端自动创建） */
    private String planId;
    private String courseId;
    private Integer profileVersion;
    /** 完整的大计划 JSON（含用户编辑后的 modules/edges/summary） */
    private String planJson;
    /** 关联的对话会话ID */
    private String chatId;
    /** AI 总结的用户需求文本 */
    private String requirementText;
}
