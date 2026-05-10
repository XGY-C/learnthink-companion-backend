package com.learnthink.core.agent.framework;

import java.util.List;
import java.util.Map;

/**
 * Structured result from an agent execution, carrying trace data for observability.
 */
public record AgentResult<O>(
    O output,
    boolean success,
    String errorMessage,
    TokenUsage tokenUsage,
    long elapsedMs,
    Map<String, Object> trace
) {
    public static <O> AgentResult<O> of(O output) {
        return new AgentResult<>(output, true, null, TokenUsage.ZERO, 0, Map.of());
    }

    public static <O> AgentResult<O> of(O output, TokenUsage usage, long elapsedMs) {
        return new AgentResult<>(output, true, null, usage, elapsedMs, Map.of());
    }

    public static <O> AgentResult<O> of(O output, TokenUsage usage, long elapsedMs, Map<String, Object> trace) {
        return new AgentResult<>(output, true, null, usage, elapsedMs, trace);
    }

    public static <O> AgentResult<O> error(String message) {
        return new AgentResult<>(null, false, message, TokenUsage.ZERO, 0, Map.of());
    }

    public AgentResult<O> withTrace(String key, Object value) {
        var newTrace = new java.util.LinkedHashMap<>(trace);
        newTrace.put(key, value);
        return new AgentResult<>(output, success, errorMessage, tokenUsage, elapsedMs, newTrace);
    }

    /**
     * LLM token usage for this agent invocation.
     */
    public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
        public static final TokenUsage ZERO = new TokenUsage(0, 0, 0);
        public static TokenUsage of(int prompt, int completion) {
            return new TokenUsage(prompt, completion, prompt + completion);
        }
    }
}
