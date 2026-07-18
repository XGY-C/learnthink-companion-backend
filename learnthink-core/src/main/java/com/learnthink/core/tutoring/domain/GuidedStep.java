package com.learnthink.core.tutoring.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GuidedStep(
    @JsonProperty(required = true) String id,
    @JsonProperty(required = true) int order,
    @JsonProperty(required = true) String stage,
    @JsonProperty(required = true) String title,
    @JsonProperty(required = true) String guidanceHint,
    @JsonProperty(required = true) String expectedAnswer,
    @JsonProperty(required = true) List<String> hintChain,
    @JsonProperty(required = true) boolean allowRevealAnswer,
    String resourceRefs
) {}
