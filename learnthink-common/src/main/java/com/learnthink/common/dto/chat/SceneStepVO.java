package com.learnthink.common.dto.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 场景动画步骤——精确控制子动画的时间点
 *
 * @author 谢光益
 * @since 2026/5/30
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SceneStepVO {

    /** 相对场景开始的时间(ms) */
    private int at;

    /** 动画指令：line-expand / text-reveal / fade-in / stroke-draw / highlight / slide-in */
    private String action;

    /** 目标元素选择器（如 .title、.bullet-1） */
    private String selector;

    /** 动画参数（可选，如 {"mode": "char-by-char"}） */
    private Map<String, Object> payload;
}
