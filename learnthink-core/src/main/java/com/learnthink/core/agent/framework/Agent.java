package com.learnthink.core.agent.framework;

/**
 * Enhanced agent interface with structured I/O, context, and tracing.
 * Replaces the legacy {@code String execute(String)} contract.
 *
 * @param <I> input type
 * @param <O> output type
 */
public interface Agent<I, O> {
    /** Unique name for this agent (used in tracing and logging) */
    String name();

    /** Execute the agent with input and context, returning a structured result */
    AgentResult<O> execute(I input, AgentContext ctx);

    /** Fallback handler invoked when {@code execute} throws. Default returns error result. */
    default AgentResult<O> onError(I input, AgentContext ctx, Throwable error) {
        return AgentResult.error(error.getMessage());
    }

    /** Whether this agent supports retry on failure. Default: false. */
    default boolean isRetryable() { return false; }

    /** Maximum number of retries. Default: 1. */
    default int maxRetries() { return 1; }
}
