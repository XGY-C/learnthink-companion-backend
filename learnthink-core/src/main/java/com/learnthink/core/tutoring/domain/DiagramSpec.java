package com.learnthink.core.tutoring.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagramSpec(
    @JsonProperty(required = true) String id,
    @JsonProperty(required = true) String type,
    @JsonProperty(required = true) String tool,
    @JsonProperty(required = true) int priority,
    @JsonProperty(required = true) String aspectRatio,
    @JsonProperty(required = true) String description,
    String sectionId
) {
    public DiagramSpec withSectionId(String sectionId) {
        return new DiagramSpec(id, type, tool, priority, aspectRatio, description, sectionId);
    }
}
