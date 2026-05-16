package com.learnthink.core.domain.dto;

import lombok.Data;

/**
 * 讲解视频DTO
 */
@Data
public class ExplanationVideoDTO {
    /**
     * 视频地址
     */
    private String videoUrl;
    /**
     * 视频标题
     */
    private String title;
    /**
     * 视频时长（单位：秒）
     */
    private Integer duration;
}
