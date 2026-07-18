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

    /** 交互配置（可选）--存在则场景播完后暂停，弹出选择题等待用户答题 */
    private Map<String, Object> interactive;

    /** 交互型可视化（可选）--true 表示到 duration 暂停等用户操作后点"继续" */
    private Boolean waitForUser;

    /** 交互提示文案（可选）--显示在"继续"按钮上方 */
    private String interactHint;
}
