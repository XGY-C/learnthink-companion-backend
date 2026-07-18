package com.learnthink.common.dto.forum;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LikeRequest {
    @NotBlank(message = "目标类型不能为空")
    private String targetType;

    @NotBlank(message = "目标ID不能为空")
    private String targetId;

    @NotBlank(message = "操作类型不能为空")
    private String action;
}
