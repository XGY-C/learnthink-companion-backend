package com.learnthink.common.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessagesResponse {
    private List<ChatMessageDto> messages;
    private boolean generationReady;
    private Map<String, Object> generationMeta;
    private boolean planGenerationReady;
    private Map<String, Object> planGenerationMeta;
    private List<ActiveTaskDto> activeTasks;
    /**
     * 当 learning_plans 中存在 pending_decision/decided/completed 状态的计划时，
     * 直接返回完整 plan JSON（含 plan_id、status、modules、edges、summary），
     * 供前端 PlanEditor 渲染，避免重新调用 /plan/preview（LLM 非确定性）。
     */
    private Map<String, Object> pendingPlan;

    /**
     * 当 pendingPlan 存在时，记录原始 planOffer 所在的消息索引。
     * 前端据此将 _pendingPlan 挂载到正确的消息上，而非 fallback 到最后一条 assistant。
     */
    private Integer planOfferMessageIdx;

    /**
     * 若该 chat 关联了智能辅导会话，则返回对应 tutoring_sessions.id。
     * 前端据此加载 /tutoring/{sessionId}/history 以恢复结构化辅导视图。
     */
    private String tutoringSessionId;

    /**
     * 会话主模式：chat / lecture / resource / plan。
     * 由 getMessages() 基于会话表 type 与消息 mode 推断后填充，供前端恢复会话图标。
     */
    private String type;
}
