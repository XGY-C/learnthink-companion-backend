package com.learnthink.core.domain.dto;

import lombok.Data;

/**
 * Manim视频渲染请求DTO
 */
@Data
public class ManimVideoRenderRequest {
    // 项目信息
    private ProjectBrief projectBrief;
    // 时间场景列表
    private Object timedScenes;
}
