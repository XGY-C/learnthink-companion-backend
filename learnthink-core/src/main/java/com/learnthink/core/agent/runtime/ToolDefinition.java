package com.learnthink.core.agent.runtime;

/**
 * 工具元数据封装
 * <p>描述工具的名称、描述和参数 JSON Schema，用于生成 LLM 函数调用格式。</p>
 *
 * @param name        工具唯一名称（snake_case）
 * @param description 人类可读描述（供 LLM 决策使用）
 * @param inputSchema 参数 JSON Schema 字符串
 */
public record ToolDefinition(
        String name,
        String description,
        String inputSchema
) {
    public static ToolDefinition of(String name, String description, String inputSchema) {
        return new ToolDefinition(name, description, inputSchema);
    }
}
