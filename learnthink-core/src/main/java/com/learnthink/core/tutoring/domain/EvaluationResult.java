package com.learnthink.core.tutoring.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluationResult(
    @JsonProperty(required = true) boolean correct,
    @JsonProperty(required = true) int score,
    @JsonProperty(required = true) String feedback,
    @JsonProperty(required = true) String evaluation
) {}
