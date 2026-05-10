package com.learnthink.core.agent.framework;

import java.time.Instant;
import java.util.Map;

/**
 * Observability hook for agent execution — captures prompt, response, timing, and decisions.
 */
public interface AgentObservation {
    AgentObservation NOOP = new AgentObservation() {};

    /** Called before the LLM call with the assembled prompt */
    default void onPrompt(String agentName, String prompt, Map<String, Object> params) {}

    /** Called after the LLM call with the raw response */
    default void onResponse(String agentName, String rawResponse, long elapsedMs, AgentResult.TokenUsage tokens) {}

    /** Called when the agent makes a decision (e.g., routing, confidence level) */
    default void onDecision(String agentName, String decision, String reason) {}

    /** Called on error */
    default void onError(String agentName, Throwable error) {}
}
