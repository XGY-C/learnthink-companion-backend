package com.learnthink.common.dto.question;

import lombok.Data;

@Data
public class KpAccuracyDTO {
    private String kpId;
    private String kpName;
    private int questionCount;
    private int totalAttempts;
    private int correctCount;
    private double accuracyRate;
}
