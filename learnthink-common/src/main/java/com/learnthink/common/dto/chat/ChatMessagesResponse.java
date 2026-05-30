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
}
