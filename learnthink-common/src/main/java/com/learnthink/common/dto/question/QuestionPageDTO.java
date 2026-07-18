package com.learnthink.common.dto.question;

import lombok.Data;

import java.util.List;

@Data
public class QuestionPageDTO {
    private List<QuestionDTO> items;
    private long total;
    private int page;
    private int size;
}
