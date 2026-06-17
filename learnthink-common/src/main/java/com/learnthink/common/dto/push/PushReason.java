package com.learnthink.common.dto.push;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 推送理由 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PushReason {
    /** path_match / weakness_match / interest_match / general */
    private String dimension;
    /** 简短标签，前端直接展示（如"当前学习模块"） */
    private String label;
    /** 详细描述（如"下一模块「分类算法」的预习资源"） */
    private String detail;
}
