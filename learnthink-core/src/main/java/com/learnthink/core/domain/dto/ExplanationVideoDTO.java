package com.learnthink.core.domain.dto;

import lombok.Data;

/**
 * 讲解视频DTO
 */
@Data
public class ExplanationVideoDTO {
    /**
     * 视频地址（渲染完成后填充，提交时为 null）
     */
    private String videoUrl;
    /**
     * Manim 渲染任务 ID，用于轮询查询渲染状态
     */
    private String manimTaskId;
    /**
     * 视频标题
     */
    private String title;
    /**
     * 视频时长（单位：秒）
     */
    private Integer duration;
}
