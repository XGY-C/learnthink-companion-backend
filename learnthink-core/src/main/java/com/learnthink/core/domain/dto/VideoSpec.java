package com.learnthink.core.domain.dto;

import lombok.Data;

/**
 * 字幕参数
 */
@Data
public class VideoSpec {
    // 宽高比
    private String aspectRatio;
    // 分辨率
    private String resolution;
    // 帧率
    private int fps;
    // 背景颜色
    private String background;

}
