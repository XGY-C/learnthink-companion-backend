package com.learnthink.common.dto.practice;

import lombok.Data;

import java.util.List;

@Data
public class AiGenerateSessionRequest {
    private String courseId;
    private Integer count;
    private String focus;
    private List<String> kpIds;
    private Integer difficulty;
    private List<String> types;
}
