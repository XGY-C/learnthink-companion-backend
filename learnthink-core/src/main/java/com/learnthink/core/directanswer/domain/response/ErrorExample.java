package com.learnthink.core.directanswer.domain.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Step 6 辅助：错误示例。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorExample(
    @JsonProperty(required = true) String wrongAttempt,
    @JsonProperty(required = true) String whyWrong,
    @JsonProperty(required = true) String correction
) {}
