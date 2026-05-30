package com.learnthink.common.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 智能助手请求 — 用于讲解模式等场景
 *
 * @author 谢光益
 * @since 2026/5/26
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmartAssistantRequest {
    /** 用户提出的问题 */
    private String question;
    /** 课程ID，可选，用于关联知识库上下文 */
    private String courseId;
}
