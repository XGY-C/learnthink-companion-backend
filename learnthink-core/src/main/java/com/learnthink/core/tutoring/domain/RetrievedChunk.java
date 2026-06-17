package com.learnthink.core.tutoring.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RetrievedChunk(
    @JsonProperty(required = true) String chunkId,
    @JsonProperty(required = true) String content,
    @JsonProperty(required = true) String sourceTitle,
    @JsonProperty(required = true) String sourceSection,
    @JsonProperty(required = true) double relevanceScore
) {}
