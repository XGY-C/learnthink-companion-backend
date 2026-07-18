package com.learnthink.core.tutoring.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GuidedStepState(
    @JsonProperty String stepId,
    @JsonProperty int order,
    @JsonProperty String stage,
    @JsonProperty String title,
    @JsonProperty String guidanceContent,
    @JsonProperty String question,
    @JsonProperty String studentAnswer,
    @JsonProperty String feedback,
    @JsonProperty String hint,
    @JsonProperty String evaluation,
    @JsonProperty int attemptCount,
    @JsonProperty int maxAttempts,
    @JsonProperty long timeSpentMs,
    @JsonProperty String status
) {
    public GuidedStepState withAttemptCount(int count) {
        return new GuidedStepState(stepId, order, stage, title, guidanceContent,
            question, studentAnswer, feedback, hint, evaluation, count, maxAttempts,
            timeSpentMs, status);
    }

    public GuidedStepState withStatus(String newStatus) {
        return new GuidedStepState(stepId, order, stage, title, guidanceContent,
            question, studentAnswer, feedback, hint, evaluation, attemptCount,
            maxAttempts, timeSpentMs, newStatus);
    }

    public GuidedStepState withStudentAnswerAndFeedback(String answer, String feedback,
                                                         String hint, String evaluation) {
        return new GuidedStepState(stepId, order, stage, title, guidanceContent,
            question, answer, feedback, hint, evaluation, attemptCount,
            maxAttempts, timeSpentMs, status);
    }
}
