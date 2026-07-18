package com.learnthink.core.directanswer.domain.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Step 6 辅助：变式题。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VariantQuestion(
    @JsonProperty(required = true) String question,
    @JsonProperty(required = true) String variation,
    String answerHint
) {}
