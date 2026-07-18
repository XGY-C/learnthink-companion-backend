package com.learnthink.common.dto.practice;

import lombok.Data;

import java.util.List;

@Data
public class PracticeSessionDTO {
    private String id;
    private String sessionType;
    private Integer questionCount;
    private Integer correctCount;
    private Integer totalDurationSeconds;
    private Boolean completed;
    private String createdAt;
    private String completedAt;
    private Integer aiGeneratedCount;
    private String evaluation;
    private List<SessionQuestionDTO> questions;
}
