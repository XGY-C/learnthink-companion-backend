package com.learnthink.common.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSendRequest {
    /** 用户发送的消息内容 */
    private String content;
    /** 课程ID，首次消息时用于延迟创建会话，必填 */
    private String courseId;
    /**
     * 聊天模式："chat"（默认）、"resource"（资源生成）、"plan"（学习规划）
     * <p>控制系统在用户消息中检测何种意图。</p>
     */
    private String mode;
    /**
     * 对话模式："standard"（planner→generator 两阶段，默认）或
     * "unified"（单次 LLM 调用完成思考 + 工具调用 + 回复生成）
     * <p>为空时回退到 learnthink.dialogue-mode 全局配置。</p>
     */
    private String dialogueMode;
    /**
     * 为 true 时跳过该消息的生成意图检测
     * <p>用于确认消息（如"确认生成xxx"），此时生成任务已在客户端启动。</p>
     */
    private Boolean skipGenerationIntent;
}
