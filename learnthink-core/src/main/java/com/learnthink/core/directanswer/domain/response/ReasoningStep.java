package com.learnthink.core.directanswer.domain.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Step 4 辅助：推理步骤节点。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReasoningStep(
    @JsonProperty(required = true) String id,
    @JsonProperty(required = true) String label,
    @JsonProperty(required = true) String thinking,
    @JsonProperty(required = true) String expression,
    @JsonProperty(required = true) String result,
    @JsonProperty(required = true) String type,
    String explanation,
    String knowledgeCardId
) {}
