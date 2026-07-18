package com.learnthink.common.dto.user;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LearningHeartbeatRequest {
    @NotBlank
    private String courseId;
    /** 本次心跳累计的学习秒数，通常为 30 */
    @Min(1) @Max(300)
    private Integer deltaSeconds;
}
