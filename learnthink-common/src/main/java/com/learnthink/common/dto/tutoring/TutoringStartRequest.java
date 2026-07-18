package com.learnthink.common.dto.tutoring;

public record TutoringStartRequest(
    String question,
    String sessionId,
    String chatId,
    String courseId,
    ClarificationResponse clarificationResponse,
    String mode
) {}
