package com.learnthink.core.directanswer.domain.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Step 3 辅助：思考步骤卡片。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThinkingStep(
    @JsonProperty(required = true) String id,
    @JsonProperty(required = true) String label,
    @JsonProperty(required = true) String description,
    @JsonProperty(required = true) String type
) {}
