package com.learnthink.core.agent.framework;

/**
 * Pipeline-stage processor with structured I/O, context, and tracing.
 *
 * <p>Implementations are single-step nodes composed via {@code StateGraph} —
 * they accept structured input, call LLM / external services, and return
 * structured output. The graph engine handles routing, retries, and feedback
 * loops. For autonomous multi-step reasoning with tool selection, see the
 * future {@code agent/autonomous/} package.</p>
 *
 * @param <I> input type
 * @param <O> output type
 * @deprecated Current pipeline-stage components use concrete methods
 *             (e.g. {@code summarize()}, {@code plan()}) rather than this
 *             generic interface. Kept as a reference for future autonomous
 *             agent abstraction.
 */
@Deprecated
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
