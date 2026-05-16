package com.learnthink.core.domain.dto;

import lombok.Data;

/**
 * 视频输入参数
 */
@Data
public class ProjectInput {
    // 主题
    private String topic;
    // 受众
    private String audience;
    // 目标
    private String goal;
    // 大纲
    private String outline;
    // 风格
    private String style;
    // 目标时长(秒)
    private Integer targetDurationSec;
    // 语言
    private String language;
}
