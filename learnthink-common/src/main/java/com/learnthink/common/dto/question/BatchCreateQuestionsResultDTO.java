package com.learnthink.common.dto.question;

import lombok.Data;

import java.util.List;

@Data
public class BatchCreateQuestionsResultDTO {
    private int addedCount;
    private int skippedCount;
    private List<QuestionDTO> items;
}
