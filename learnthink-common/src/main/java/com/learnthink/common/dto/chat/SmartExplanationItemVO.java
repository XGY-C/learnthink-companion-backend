package com.learnthink.common.dto.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 智能讲解项——AI 输出的单个讲解帧（兼容旧板书格式和新 Scene 格式）
 *
 * @author 谢光益
 * @since 2026/5/30
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SmartExplanationItemVO {

    /** 场景序号（从 0 开始，Scene 格式使用） */
    private Integer sceneIndex;

    /** 场景数据（新 Scene 协议，优先使用） */
    private SceneVO scene;

    /** 语音合成文本（旧板书格式） */
    private String speech;

    /** 音频 URL（TTS 生成后回填） */
    private String audioUrl;

    /** 动作开始时间（ms），TTS 生成后回填 */
    private String beginTime;

    /** 动作结束时间（ms），TTS 生成后回填 */
    private String endTime;

    /** 交互行为（旧板书格式） */
    private ActionVO action;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ActionVO {
        /** 动作类型：text | image | chart | shape */
        private String type;

        /** 板书文字内容，公式用 $...$ */
        private String content;

        /** 坐标位置 */
        private PositionVO position;

        /** 参数 */
        private ParamsVO params;

        /** 多媒体 */
        private MediaVO media;

        /** 样式预设标签 */
        private String style;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PositionVO {
        @JsonProperty("x")
        private int x;

        @JsonProperty("y")
        private int y;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ParamsVO {
        @JsonProperty("data")
        private List<Integer> data;

        @JsonProperty("highlight_indices")
        private List<Integer> highlightIndices;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MediaVO {
        /** 多媒体格式：none | image | svg */
        private String format;

        /** 多媒体 URL 或图片描述文本 */
        private String url;
    }
}
