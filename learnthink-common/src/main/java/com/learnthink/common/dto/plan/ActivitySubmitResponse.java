package com.learnthink.common.dto.plan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * Activity 提交响应 (v3.0)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivitySubmitResponse {
    private String activityId;
    private Boolean activityCompleted;
    private String status;
    private Double score;
    private List<String> weakTags;

    /** quiz 边缘未达标时有值 */
    private Integer retryCount;
    private Boolean retryAllowed;
    private Integer retriesRemaining;

    private String moduleStatus;
    private Double moduleMastery;

    /** quiz 严重未达标时非 null */
    private AutoActionDto autoAction;

    /** 逐题评判结果 */
    private List<QuestionResult> questionResults;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionResult {
        private String questionId;
        /** correct / incorrect / partial */
        private String result;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AutoActionDto {
        private String type;
        private String reason;
        private List<InsertedActivityDto> insertedActivities;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InsertedActivityDto {
        private String activityId;
        private String type;
        private String title;
    }
}
