package com.learnthink.core.domain.dto;

import lombok.Data;
import java.util.List;

/**
 * 项目简介
 */
@Data
public class ProjectBrief {
    private String projectId;
    private String topic;
    private String audience;
    private String goal;
    private String outline;
    private String style;
    private Integer targetDurationSec;
    private String language;
    private String manimVersion;
    private VideoSpec videoSpec;
    private SubtitleSpec subtitleSpec;
    private List<String> designRules;
    private CodeStyleGuide codeStyleGuide;
}
