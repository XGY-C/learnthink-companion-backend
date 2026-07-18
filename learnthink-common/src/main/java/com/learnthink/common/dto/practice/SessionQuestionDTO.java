package com.learnthink.common.dto.practice;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class SessionQuestionDTO {
    private String itemId;
    private Integer sortOrder;
    private String questionId;
    private String questionType;
    private Integer difficulty;
    private String title;
    private List<Map<String, String>> options;
    private String kpId;
    private String kpName;
    private Boolean answered;
    private Boolean isCorrect;
    private Object correctAnswer;
    private String explanation;
    private Object userAnswer;
}
