package com.learnthink.common.dto.push;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 评分结果 DTO — 单个被评分资源包，包含三个排序键和推送理由
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScoredPack {
    private String packId;
    private String title;
    private String knowledgePoint;
    private double pathMatch;
    private double weaknessMatch;
    private double interestMatch;
    private String confidence;
    private int estimatedMinutes;
    private int resourceCount;
    private List<PushReason> reasons;
    private String createdAt;
}
