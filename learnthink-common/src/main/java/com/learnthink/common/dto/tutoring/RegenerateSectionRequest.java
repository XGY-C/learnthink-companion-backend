package com.learnthink.common.dto.tutoring;

public record RegenerateSectionRequest(
    String sectionId,
    String action,
    String instruction
) {}
