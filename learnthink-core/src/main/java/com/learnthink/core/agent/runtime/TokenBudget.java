package com.learnthink.core.agent.runtime;

/**
 * Agent 调用的 Token 预算，防止 LLM 调用失控
 */
public record TokenBudget(
    int maxTotalTokens,
    int maxPromptTokens,
    boolean isUnlimited
) {
    public static TokenBudget of(int maxTotalTokens) {
        return new TokenBudget(maxTotalTokens, maxTotalTokens, false);
    }

    public static TokenBudget of(int maxTotalTokens, int maxPromptTokens) {
        return new TokenBudget(maxTotalTokens, maxPromptTokens, false);
    }

    public static TokenBudget unlimited() {
        return new TokenBudget(Integer.MAX_VALUE, Integer.MAX_VALUE, true);
    }
}
