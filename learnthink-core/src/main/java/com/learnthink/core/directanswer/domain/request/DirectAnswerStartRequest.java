package com.learnthink.core.directanswer.domain.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DirectAnswer 启动请求 DTO。
 */
public record DirectAnswerStartRequest(
    @JsonProperty(required = true) String question,
    @JsonProperty(required = true) String courseId,
    @JsonProperty(required = true) String mode,       // "direct_answer" | "smart"
    String chatId,
    String sessionId
) {}
