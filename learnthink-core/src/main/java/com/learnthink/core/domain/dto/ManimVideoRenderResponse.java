package com.learnthink.core.domain.dto;

import lombok.Data;

/**
 * Manim视频渲染响应DTO
 * @author 谢光益
 * @since 2026/4/6
 */
@Data
public class ManimVideoRenderResponse {
    // 任务ID
    private String taskId;
    // 是否成功
    private Boolean success;
    // 任务状态
    private String status;
    // 视频URL
    private String videoUrl;
    // OSS对象Key
    private String ossObjectKey;
    // 任务信息
    private String message;
    // 尝试次数
    private Integer attempts;
    // 任务目录
    private String taskDir;
}