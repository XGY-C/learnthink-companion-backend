package com.learnthink.core.tutoring.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReactState(
    @JsonProperty(required = true) String sessionId,
    @JsonProperty(required = true) int iteration,
    @JsonProperty(required = true) List<Map<String, Object>> conversationHistory,
    @JsonProperty(required = true) String accumulatedQuestion,
    @JsonProperty(required = true) String originalQuestion
) {
    public ReactState incrementIteration() {
        return new ReactState(sessionId, iteration + 1, conversationHistory, accumulatedQuestion, originalQuestion);
    }
}
