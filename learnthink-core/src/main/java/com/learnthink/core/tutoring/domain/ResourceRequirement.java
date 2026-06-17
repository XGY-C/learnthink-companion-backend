package com.learnthink.core.tutoring.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceRequirement(
    @JsonProperty(required = true) String id,
    @JsonProperty(required = true) String query,
    @JsonProperty(required = true) String forSection,
    @JsonProperty(required = true) String priority,
    @JsonProperty(required = true) String expectedUse
) {}
