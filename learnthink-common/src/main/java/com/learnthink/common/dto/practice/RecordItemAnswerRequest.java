package com.learnthink.common.dto.practice;

import lombok.Data;

@Data
public class RecordItemAnswerRequest {
    private String attemptId;
    private Boolean isCorrect;
    private Integer durationSeconds;
}
