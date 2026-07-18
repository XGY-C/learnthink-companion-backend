package com.learnthink.common.dto.practice;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class CreatePracticeSessionRequest {
    private String courseId;
    private String sessionType;
    private List<String> kpIds;
    private Integer difficulty;
    private Integer questionCount;
    private List<String> questionTypes;
    private List<String> questionIds;
    /** 题型 -> 数量，非空时按题型分别抽题 */
    private Map<String, Integer> typeCounts;
}
