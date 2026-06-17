package com.learnthink.common.dto.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 视频讲解场景——AI 输出的单个场景帧
 *
 * @author 谢光益
 * @since 2026/5/30
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SceneVO {

    /** 场景类型：title / text / diagram / code / comparison / summary / ending */
    private String type;

    /** 建议播放时长(ms)，0 表示由前端自动计算 */
    private int duration;

    /** 转场类型：fade / slide-left / slide-up / zoom / none */
    private String transition;

    /** TTS 朗读文本 */
    private String narration;

    /** 场景内容（结构因 type 而异） */
    private Map<String, Object> content;

    /** 精确动画步骤控制（可选） */
    private List<SceneStepVO> steps;

    /** TTS 合成后的音频 URL */
    private String audioUrl;

    /** 场景画布背景（可选，覆盖默认） */
    private String background;
}
