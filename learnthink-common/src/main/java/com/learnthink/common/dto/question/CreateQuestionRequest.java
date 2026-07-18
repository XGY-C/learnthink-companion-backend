package com.learnthink.common.dto.question;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class CreateQuestionRequest {
    private String courseId;
    private String sourceItemId;

    private String questionType;
    private Integer difficulty;
    private String title;
    private List<Map<String, String>> options;
    private Object answer;
    private String explanation;

    private String kpId;
    private List<String> tags;
}
