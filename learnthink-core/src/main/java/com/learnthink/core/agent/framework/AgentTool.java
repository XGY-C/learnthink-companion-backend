package com.learnthink.core.agent.framework;

/**
 * A tool that an agent can invoke. Agents with tools can delegate
 * specific work to external services or specialized sub-agents.
 */
public interface AgentTool {
    /** Unique tool name, used in function-calling schemas */
    String name();

    /** Human-readable description for the LLM's function-calling prompt */
    String description();

    /** JSON Schema string describing the tool's parameters */
    String parameterSchema();

    /** Execute the tool with JSON arguments, returning JSON result */
    String execute(String jsonArgs);
}
