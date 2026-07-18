package com.learnthink.core.agent.runtime;

import java.util.List;
import java.util.Map;

/**
 * 标准化工具执行返回值
 * <p>所有工具执行后返回此对象，统一封装返回内容、引用来源、元数据和控制信号。</p>
 *
 * @param content       返回给 LLM 的文本内容
 * @param sources       引用来源列表（如检索片段、网页 URL 等）
 * @param metadata      自由元数据（用于 UI 展示或追踪）
 * @param success       是否执行成功
 * @param terminateTurn 是否终止当前轮次（保留扩展，暂不使用）
 * @param pauseForUser  暂停等待用户输入（ask_user 用），null 表示不暂停
 */
public record ToolResult(
        String content,
        List<Map<String, Object>> sources,
        Map<String, Object> metadata,
        boolean success,
        boolean terminateTurn,
        Map<String, Object> pauseForUser
) {
    /** 空结果常量 */
    public static final ToolResult EMPTY = new ToolResult("", List.of(), Map.of(), true, false, null);

    /** 快速创建成功结果 */
    public static ToolResult success(String content) {
        return new ToolResult(content, List.of(), Map.of(), true, false, null);
    }

    /** 快速创建成功结果（带来源） */
    public static ToolResult success(String content, List<Map<String, Object>> sources) {
        return new ToolResult(content, sources, Map.of(), true, false, null);
    }

    /** 快速创建成功结果（带来源和元数据） */
    public static ToolResult success(String content, List<Map<String, Object>> sources,
                                     Map<String, Object> metadata) {
        return new ToolResult(content, sources, metadata, true, false, null);
    }

    /** 快速创建失败结果 */
    public static ToolResult error(String message) {
        return new ToolResult(message, List.of(), Map.of("error", message), false, false, null);
    }

    /** 快速创建暂停结果（等待用户输入） */
    public static ToolResult pause(Map<String, Object> pausePayload) {
        return new ToolResult("", List.of(), Map.of(), true, false, pausePayload);
    }

    @Override
    public String toString() {
        return content;
    }
}
