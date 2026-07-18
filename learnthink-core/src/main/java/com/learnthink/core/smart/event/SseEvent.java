package com.learnthink.core.smart.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Smart 模式 SSE 事件。
 * <p>统一表示所有 Smart 模式 SSE 事件，由 flatMap 统一 flush。</p>
 *
 * <h3>事件类型</h3>
 * <ul>
 *   <li>{@code agent.thought}     - 思考过程</li>
 *   <li>{@code agent.tool_call}   - 工具调用开始</li>
 *   <li>{@code agent.tool_result} - 工具结果</li>
 *   <li>{@code agent.visual}      - 可视化产物</li>
 *   <li>{@code chunk}             - 文本输出（原始文本，非 JSON）</li>
 *   <li>{@code smart.state}       - 状态更新</li>
 *   <li>{@code smart.converged}   - 收敛信号</li>
 *   <li>{@code smart.started}     - 首次启动</li>
 *   <li>{@code done}              - 本轮完成</li>
 *   <li>{@code error}             - 错误</li>
 * </ul>
 */
public record SseEvent(String name, Object data, boolean rawText) {

    // ── 工厂方法 ──────────────────────────────────────────

    /** 思考事件 */
    public static SseEvent thought(String phase, String content) {
        return new SseEvent("agent.thought", Map.of(
            "phase", phase,
            "content", content,
            "timestamp", Instant.now().toString()
        ), false);
    }

    /** 工具调用开始 */
    public static SseEvent toolCallStart(String toolName, String args) {
        return new SseEvent("agent.tool_call", Map.of(
            "tool", toolName,
            "args", truncate(args, 500),
            "timestamp", Instant.now().toString()
        ), false);
    }

    /** 工具结果 */
    public static SseEvent toolResult(String toolName, String resultSummary, boolean success) {
        return new SseEvent("agent.tool_result", Map.of(
            "tool", toolName,
            "result", truncate(resultSummary, 1000),
            "success", success,
            "timestamp", Instant.now().toString()
        ), false);
    }

    /** 工具结果（带搜索来源，用于 web_search） */
    public static SseEvent toolResultWithSources(String toolName, String resultSummary,
                                                  boolean success, List<Map<String, Object>> sources) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", toolName);
        data.put("result", truncate(resultSummary, 1000));
        data.put("success", success);
        data.put("sources", sources != null ? sources : List.of());
        data.put("timestamp", Instant.now().toString());
        return new SseEvent("agent.tool_result", data, false);
    }

    /** 可视化产物 */
    public static SseEvent visual(String toolName, String renderType, String code, String description) {
        return new SseEvent("agent.visual", Map.of(
            "renderType", renderType,
            "code", code,
            "description", description != null ? description : "",
            "tool", toolName,
            "timestamp", Instant.now().toString()
        ), false);
    }

    /** 文本输出（原始文本） */
    public static SseEvent chunk(String text) {
        return new SseEvent("chunk", text, true);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
