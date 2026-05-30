package com.learnthink.common.dto.chat;

/**
 * Agent 思考阶段枚举 —— 前后端共享契约，所有 agent.thought SSE 事件的 phase 字段取值来源。
 * 新增阶段必须同步更新前端 ThinkingPhase 类型及 phaseLabels 映射。
 */
public enum ThinkingPhase {
    CONTEXT,
    RETRIEVE,
    RAG,
    PLANNING,
    DECISION,
    REFLECT,
    ERROR;

    public static ThinkingPhase fromString(String s) {
        if (s == null) return null;
        try {
            return valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
