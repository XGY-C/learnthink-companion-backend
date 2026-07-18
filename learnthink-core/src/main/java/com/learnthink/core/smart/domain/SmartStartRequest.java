package com.learnthink.core.smart.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Smart 模式启动请求。
 */
public record SmartStartRequest(
    @JsonProperty(required = true) String question,
    @JsonProperty(required = true) String courseId,
    @JsonProperty String chatId,
    @JsonProperty String sessionId
) {}
