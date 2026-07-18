package com.learnthink.common.dto.question;

import lombok.Data;

@Data
public class SubmitAnswerRequest {
    private String courseId;
    private String questionId;
    private Object selectedAnswer;
    private Integer durationSeconds;
}
