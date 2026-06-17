package com.learnthink.core.tutoring.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuestionAnalysis(
    @JsonProperty(required = true) String questionType,
    @JsonProperty(required = true) String domain,
    @JsonProperty(required = true) String subDomain,
    @JsonProperty(required = true) String difficulty,
    @JsonProperty(required = true) List<String> keyConcepts,
    @JsonProperty(required = true) List<String> prerequisiteGaps,
    @JsonProperty(required = true) String realIntent
) {}
