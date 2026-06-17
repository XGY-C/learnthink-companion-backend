package com.learnthink.common.dto.tutoring;

public record ClarificationResponse(
    boolean skipped,
    String selectedOptionId,
    String freeInput
) {}
