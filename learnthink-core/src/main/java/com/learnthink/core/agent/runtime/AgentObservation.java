package com.learnthink.core.agent.runtime;

import java.util.Map;

/**
 * Agent 执行的可观测性钩子——捕获提示词、响应、耗时和决策
 */
public interface AgentObservation {
    AgentObservation NOOP = new AgentObservation() {};

    /** 在 LLM 调用前使用组装好的提示词调用 */
    default void onPrompt(String agentName, String prompt, Map<String, Object> params) {}

    /** 在 LLM 调用后使用原始响应调用 */
    default void onResponse(String agentName, String rawResponse, long elapsedMs, AgentResult.TokenUsage tokens) {}

    /** 当 Agent 做出决策时调用（如路由、置信度级别） */
    default void onDecision(String agentName, String decision, String reason) {}

    /** 出错时调用 */
    default void onError(String agentName, Throwable error) {}

    /**
     * Agent 进行推理/思考时调用（非决策性质的思考过程）。
     * 与 {@link #onDecision} 互补：onThink 记录思考过程，onDecision 记录决策动作。
     *
     * @param agentName  Agent 名称
     * @param thought   思考内容（如 LLM 的 CoT 推理、检索策略权衡）
     */
    default void onThink(String agentName, String thought) {}
}
