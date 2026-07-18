package com.learnthink.core.directanswer.domain.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Step 5 辅助：迁移示例。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferExample(
    @JsonProperty(required = true) String scenario,
    @JsonProperty(required = true) String question,
    @JsonProperty(required = true) String solutionHint
) {}
