package com.learnthink.core.domain.dto;

import lombok.Data;

/**
 * 字幕参数
 */
@Data
public class SubtitleSpec {
    private boolean enabled = true;
    private String position;
    private int maxLines;
    private int fontSize;
}
