package com.learnthink.core.tutoring.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClarificationOption(
    @JsonProperty(required = true) String id,
    @JsonProperty(required = true) String label,
    @JsonProperty(required = true) String detail,
    @JsonProperty(required = true) String prefillQuestion
) {}
