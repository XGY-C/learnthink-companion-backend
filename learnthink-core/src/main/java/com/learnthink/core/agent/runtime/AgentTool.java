package com.learnthink.core.agent.runtime;

/**
 * Agent 可调用的工具接口
 * <p>拥有工具的 Agent 可以将特定工作委派给外部服务或特化的子 Agent。</p>
 */
public interface AgentTool {
    /** 唯一工具名称，用于函数调用 Schema */
    String name();

    /** 供 LLM 函数调用提示词使用的人类可读描述 */
    String description();

    /** 描述工具参数的 JSON Schema 字符串 */
    String parameterSchema();

    /** 使用 JSON 参数执行工具，返回 JSON 结果 */
    String execute(String jsonArgs);
}
