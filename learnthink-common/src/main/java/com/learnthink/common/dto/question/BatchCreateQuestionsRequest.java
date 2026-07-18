package com.learnthink.common.dto.question;

import lombok.Data;

import java.util.List;

@Data
public class BatchCreateQuestionsRequest {
    private String courseId;
    private String sourceItemId;
    private List<CreateQuestionRequest> questions;
}
