package com.learnthink.core.tutoring.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClarificationDecision(
    @JsonProperty(required = true) String action,
    @JsonProperty(required = true) String reasoning
) {}
