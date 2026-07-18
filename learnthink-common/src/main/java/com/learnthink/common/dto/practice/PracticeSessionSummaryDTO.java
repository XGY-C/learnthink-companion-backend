package com.learnthink.common.dto.practice;

import lombok.Data;

@Data
public class PracticeSessionSummaryDTO {
    private String id;
    private String sessionType;
    private Integer questionCount;
    private Integer correctCount;
    private Integer totalDurationSeconds;
    private Boolean completed;
    private String createdAt;
    private String completedAt;
    private Double accuracyRate;
}
