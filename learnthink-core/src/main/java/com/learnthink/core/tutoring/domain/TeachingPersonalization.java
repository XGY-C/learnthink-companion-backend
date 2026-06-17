package com.learnthink.core.tutoring.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeachingPersonalization(
    @JsonProperty(required = true) String strategy,
    @JsonProperty(required = true) String depth,
    @JsonProperty(required = true) Map<String, Object> adaptations
) {}
