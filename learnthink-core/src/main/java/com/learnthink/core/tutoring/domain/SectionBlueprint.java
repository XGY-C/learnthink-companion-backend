package com.learnthink.core.tutoring.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SectionBlueprint(
    @JsonProperty(required = true) String id,
    @JsonProperty(required = true) String title,
    @JsonProperty(required = true) boolean expandDefault,
    @JsonProperty(required = true) String purpose,
    @JsonProperty(required = true) List<String> resourceRefs,
    ExpectedDiagram expectedDiagram,
    @JsonProperty(required = true) String generationHint
) {}
