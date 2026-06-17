package com.learnthink.core.tutoring.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Clarification(
    @JsonProperty(required = true) String understoodPart,
    @JsonProperty(required = true) String ambiguityDescription,
    @JsonProperty(required = true) List<ClarificationOption> options,
    @JsonProperty(required = true) boolean allowFreeInput
) {}
