package com.learnthink.core.agent.orchestration;

import com.learnthink.core.agent.runtime.AgentObservation;

/**
 * 将 Agent 观测事件桥接到 SSE 事件广播器，供前端"Agent 思考链"面板展示。
 *
 * <p>约定：调用方在每次流水线阶段切换时调用 {@link #setPipelineStage} 和
 * {@link #setCurrentTaskDesc}，确保后续事件携带正确的上下文。</p>
 */
public class SseAgentObservation implements AgentObservation {

    private final String taskId;
    private final TaskEventBroadcaster broadcaster;

    /** 当前流水线阶段（如 PROFILING / RETRIEVING / GENERATING / REVIEWING） */
    private volatile String pipelineStage = "";
    /** 当前任务描述（如 "生成文档「A*算法详解」，难度=medium，证据=8条"） */
    private volatile String currentTaskDesc = "";

    public SseAgentObservation(String taskId, TaskEventBroadcaster broadcaster) {
        this.taskId = taskId;
        this.broadcaster = broadcaster;
    }

    // ── 供调用方设置上下文 ──

    public void setPipelineStage(String stage) {
        this.pipelineStage = stage != null ? stage : "";
    }

    public void setCurrentTaskDesc(String desc) {
        this.currentTaskDesc = desc != null ? desc : "";
    }

    // ── AgentObservation 实现 ──

    @Override
    public void onThink(String agentName, String thought) {
        if (broadcaster == null) return;
        broadcaster.agentThought(
            taskId, agentName, agentName,
            currentTaskDesc,     // context
            "",                  // observation — 思考型事件无观察
            thought,             // thought — 核心内容
            "",                  // decision — 思考型事件无决策
            pipelineStage,       // pipelineStage
            "medium");
    }

    @Override
    public void onDecision(String agentName, String decision, String reason) {
        if (broadcaster == null) return;
        broadcaster.agentThought(
            taskId, agentName, agentName,
            currentTaskDesc,     // context
            reason,              // observation — Agent 观察到的上下文/事实
            "",                  // thought — 决策型事件不填思考内容
            decision,            // decision
            pipelineStage,
            deriveConfidence(decision));
    }

    @Override
    public void onError(String agentName, Throwable error) {
        if (broadcaster == null) return;
        broadcaster.agentThought(
            taskId, agentName, agentName,
            currentTaskDesc,
            error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName(),
            "",
            "ERROR",
            pipelineStage,
            "low");
    }

    private String deriveConfidence(String decision) {
        if (decision == null) return "medium";
        return switch (decision) {
            case "PUBLISH", "SELF_REVIEW_PASS", "COMPLETE" -> "high";
            case "RETRY", "SELF_REVISE", "FACT_CHECK_ISSUES" -> "medium";
            case "REJECT_PERMANENT", "TIMEOUT", "ERROR" -> "low";
            default -> "medium";
        };
    }
}
