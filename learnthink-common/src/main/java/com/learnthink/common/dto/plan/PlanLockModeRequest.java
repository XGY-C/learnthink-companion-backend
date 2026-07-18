package com.learnthink.common.dto.plan;

import lombok.Data;

@Data
public class PlanLockModeRequest {
    private String courseId;       // 必填
    private String lockMode;       // sequential / free
}
