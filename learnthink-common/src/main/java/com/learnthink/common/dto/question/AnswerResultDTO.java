package com.learnthink.common.dto.question;

import lombok.Data;

@Data
public class AnswerResultDTO {
    private boolean correct;
    private Object correctAnswer;
    private String explanation;
    private int attemptNumber;
    private int totalAttempts;
    private String kpId;
    private String kpName;
    private String attemptId;
}
