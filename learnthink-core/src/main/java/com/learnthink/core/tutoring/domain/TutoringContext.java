package com.learnthink.core.tutoring.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TutoringContext(
    String userId,
    String question,
    String sessionId,
    Map<String, Object> profileSnapshot,
    Map<String, Object> pathPosition,
    List<Map<String, Object>> recentLearning,
    List<Map<String, Object>> recentTutoring,
    ReactState reactState,
    ClarificationResponse clarificationResponse,
    String subMode
) {
    public TutoringContext withQuestion(String question) {
        return new TutoringContext(userId, question, sessionId, profileSnapshot, pathPosition,
            recentLearning, recentTutoring, reactState, clarificationResponse, subMode);
    }

    public TutoringContext withReactState(ReactState reactState) {
        return new TutoringContext(userId, question, sessionId, profileSnapshot, pathPosition,
            recentLearning, recentTutoring, reactState, clarificationResponse, subMode);
    }

    public TutoringContext withClarificationResponse(ClarificationResponse clarificationResponse) {
        return new TutoringContext(userId, question, sessionId, profileSnapshot, pathPosition,
            recentLearning, recentTutoring, reactState, clarificationResponse, subMode);
    }

    public TutoringContext withSubMode(String subMode) {
        return new TutoringContext(userId, question, sessionId, profileSnapshot, pathPosition,
            recentLearning, recentTutoring, reactState, clarificationResponse, subMode);
    }

    public record ClarificationResponse(
        boolean skipped,
        String selectedOptionId,
        String freeInput
    ) {}
}
