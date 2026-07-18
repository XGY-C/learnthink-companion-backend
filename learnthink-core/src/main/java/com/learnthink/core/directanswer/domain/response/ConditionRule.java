package com.learnthink.core.directanswer.domain.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Step 5 辅助：条件规则（方法适用条件）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConditionRule(
    @JsonProperty(required = true) String condition,
    @JsonProperty(required = true) String method
) {}
