package com.learnthink.common.dto.plan;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Activity 提交通用请求 (v3.0)
 * quiz 类型传 answers，learn/explore 类型传 duration + interaction
 */
@Data
public class ActivitySubmitRequest {
    /** quiz 类型：逐题作答 */
    private List<AnswerItem> answers;

    /** learn/explore 类型：停留秒数 */
    @JsonProperty("duration_seconds")
    private Integer durationSeconds;

    /** learn 类型：是否检测到页面交互 */
    @JsonProperty("interaction_detected")
    private Boolean interactionDetected;

    @Data
    public static class AnswerItem {
        @JsonProperty("question_id")
        private String questionId;
        private String answer;
    }
}
