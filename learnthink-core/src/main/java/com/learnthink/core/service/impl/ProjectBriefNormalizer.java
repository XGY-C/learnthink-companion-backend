package com.learnthink.core.service.impl;
import com.learnthink.core.domain.dto.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 视频参数标准化服务
 * @author 谢光益
 * @since 2026/4/6
 */
@Service
public class ProjectBriefNormalizer {

    /**
     * 需求标准化
     * 将用户原始输入标准化为 projectBrief
     */
    public ProjectBrief normalize(ProjectInput raw) {
        if (raw == null) {
            throw new IllegalArgumentException("project input cannot be null");
        }

        String topic = requireNonBlank(raw.getTopic(), "topic 不能为空");
        String audience = defaultIfBlank(raw.getAudience(), "普通科普观众");
        String goal = defaultIfBlank(raw.getGoal(), "帮助观众理解该主题的核心概念");
        String outline = raw.getOutline();
        String style = raw.getStyle();
        // 目标时长(秒), 如果为空,默认值为90秒
        Integer targetDurationSec = defaultIfNull(raw.getTargetDurationSec(), 90);
        String language = defaultIfBlank(raw.getLanguage(), "中文");

        ProjectBrief brief = new ProjectBrief();
        brief.setProjectId(generateProjectId());
        brief.setTopic(topic);
        brief.setAudience(audience);
        brief.setGoal(goal);
        brief.setOutline(outline);
        brief.setStyle(style);
        brief.setTargetDurationSec(targetDurationSec);
        brief.setLanguage(language);
        brief.setManimVersion("0.20.1");
        brief.setVideoSpec(buildVideoSpec());
        brief.setSubtitleSpec(buildSubtitleSpec());
        brief.setDesignRules(buildDesignRules());
        brief.setCodeStyleGuide(buildCodeStyleGuide());

        return brief;
    }

    private static String generateProjectId() {
        // 简单版本：P + 8位随机
        return "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return (value == null || value.trim().isEmpty()) ? defaultValue : value.trim();
    }

    private static Integer defaultIfNull(Integer value, Integer defaultValue) {
        return value == null ? defaultValue : value;
    }



    /**
     * 支持：
     * 90
     * "90"
     * "90s"
     * "90sec"
     * "90秒"
     */
    private static int parseTargetDurationSec(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }

        if (value instanceof Number) {
            int sec = ((Number) value).intValue();
            return sec > 0 ? sec : defaultValue;
        }

        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return defaultValue;
        }

        text = text
                .replace("seconds", "")
                .replace("second", "")
                .replace("secs", "")
                .replace("sec", "")
                .replace("s", "")
                .replace("秒", "")
                .trim();

        try {
            int sec = Integer.parseInt(text);
            return sec > 0 ? sec : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private VideoSpec buildVideoSpec() {
        VideoSpec spec = new VideoSpec();
        spec.setAspectRatio("16:9");
        spec.setResolution("1920x1080");
        spec.setFps(30);
        spec.setBackground("#0B1020");
        return spec;
    }

    private SubtitleSpec buildSubtitleSpec() {
        SubtitleSpec spec = new SubtitleSpec();
        spec.setEnabled(true);
        spec.setPosition("bottom");
        spec.setMaxLines(2);
        spec.setFontSize(30);
        return spec;
    }

    private List<String> buildDesignRules() {
        LinkedHashSet<String> rules = new LinkedHashSet<>();
        rules.add("每个 scene 只表达一个主视觉意图");
        rules.add("默认深色背景");
        rules.add("文字尽量少");
        rules.add("理解优先，不追求炫技");
        rules.add("字幕不能遮挡主视觉");

        return new ArrayList<>(rules);
    }

    private CodeStyleGuide buildCodeStyleGuide() {
        CodeStyleGuide guide = new CodeStyleGuide();
        guide.setSingleFile(true);
        guide.setMainSceneClass("GeneratedVideoScene");
        guide.setUseHelpers(true);
        guide.setPreferBasicShapes(true);
        guide.setPreferSafeAnimations(true);
        guide.setSubtitleMethod("sentence_level");

        List<String> fallbackFonts = (guide.getFontFallback() != null && !guide.getFontFallback().isEmpty())
                ? guide.getFontFallback().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList())
                : Arrays.asList("Microsoft YaHei", "SimHei", "Noto Sans CJK SC");

        guide.setFontFallback(fallbackFonts);
        return guide;
    }

}

