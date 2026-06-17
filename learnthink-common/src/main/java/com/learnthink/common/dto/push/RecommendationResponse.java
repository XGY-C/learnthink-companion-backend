package com.learnthink.common.dto.push;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Dashboard 推荐响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecommendationResponse {
    /** 主推资源包（Top-1） */
    private ScoredPack main;
    /** 次要推荐列表（Top 2-5） */
    private List<ScoredPack> secondary;
}
