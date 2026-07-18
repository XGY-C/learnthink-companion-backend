package com.learnthink.common.dto.tutoring;

public record GuidedAnswerRequest(
    String sessionId,
    String stepId,
    String action,        // "answer" | "reveal" | "hint"
    String answer         // 学生作答文本（action=answer 时必填）
) {}
