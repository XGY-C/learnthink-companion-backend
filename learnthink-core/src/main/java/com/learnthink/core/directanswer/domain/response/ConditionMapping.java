package com.learnthink.core.directanswer.domain.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Step 2 辅助：条件映射（双栏单项）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConditionMapping(
    @JsonProperty(required = true) String label,
    @JsonProperty(required = true) String value,
    @JsonProperty(required = true) String highlightType,
    String interpretation
) {}
