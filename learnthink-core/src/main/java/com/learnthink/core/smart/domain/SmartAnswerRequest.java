package com.learnthink.core.smart.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Smart 模式学生回答请求。
 */
public record SmartAnswerRequest(
    @JsonProperty(required = true) String sessionId,
    @JsonProperty(required = true) String answer,
    @JsonProperty String chatId
) {}
