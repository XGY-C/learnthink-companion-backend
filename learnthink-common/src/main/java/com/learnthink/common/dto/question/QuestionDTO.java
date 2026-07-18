package com.learnthink.common.dto.question;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class QuestionDTO {
    private String id;
    private String userId;
    private String courseId;
    private String sourceItemId;

    private String questionType;
    private Integer difficulty;
    private String title;
    private List<Map<String, String>> options;
    private Object answer;
    private String explanation;

    private String kpId;
    private String kpName;
    private List<String> tags;

    private Integer attemptCount;
    private Integer correctCount;
    private Double accuracyRate;

    private String status;
    private String createdAt;
}
