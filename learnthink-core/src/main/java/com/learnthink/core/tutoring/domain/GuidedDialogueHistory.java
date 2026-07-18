package com.learnthink.core.tutoring.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GuidedDialogueHistory(
    @JsonProperty(required = true) String sessionId,
    @JsonProperty(required = true) String originalQuestion,
    @JsonProperty(required = true) int currentStepIndex,
    @JsonProperty(required = true) List<StepTurn> turns
) {
    /** 单步交互记录 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepTurn(
        @JsonProperty String stepId,
        @JsonProperty String stage,
        @JsonProperty String title,
        @JsonProperty String guidanceContent,
        @JsonProperty String question,
        @JsonProperty String studentAnswer,
        @JsonProperty String feedback,
        @JsonProperty String evaluation,
        @JsonProperty int attempts,
        @JsonProperty List<String> hintsGiven
    ) {}

    public GuidedDialogueHistory withNewTurn(StepTurn turn) {
        var list = new ArrayList<>(turns);
        list.add(turn);
        return new GuidedDialogueHistory(sessionId, originalQuestion, currentStepIndex + 1, list);
    }
}
