package com.learnthink.core.agent.tools.callbacks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentTool;
import com.learnthink.core.agent.runtime.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 通用 ToolCallback 包装器
 * <p>将任意 {@link AgentTool} 包装为 Spring AI {@link ToolCallback}，
 * 替代为每个工具手写 Callback 的模式。</p>
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li><b>参数注入</b>：构造时传入的 injectedArgs 会合并到 LLM 提供的参数中
 *      （如 course_id、user_id 等服务端私有参数，LLM 不感知）</li>
 *   <li><b>生命周期钩子</b>：onCall / onResult 回调，用于 SSE 事件推送</li>
 *   <li><b>工具描述覆盖</b>：可选传入自定义描述，否则使用 AgentTool 原始描述</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 简单包装
 * ToolCallback cb = new DelegatingToolCallback(ragTool, Map.of("course_id", courseId));
 *
 * // 带 SSE 钩子
 * ToolCallback cb = new DelegatingToolCallback(
 *     ragTool,
 *     Map.of("course_id", courseId),
 *     () -> emitter.send("正在检索知识库..."),
 *     result -> emitter.send("检索完成")
 * );
 * }</pre>
 */
public class DelegatingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(DelegatingToolCallback.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentTool tool;
    private final Map<String, Object> injectedArgs;
    private final String descriptionOverride;
    private final List<Runnable> onCallHooks;
    private final Consumer<String> onResultHook;

    /**
     * @param tool            被包装的工具
     * @param injectedArgs    服务端注入的参数（合并到 LLM 参数之前，可被 LLM 参数覆盖）
     * @param descriptionOverride 自定义描述（null 则使用 tool.description()）
     * @param onCallHooks     工具执行前的钩子（如 SSE 事件推送）
     * @param onResultHook    工具执行后的钩子，参数为 JSON 结果字符串
     */
    public DelegatingToolCallback(AgentTool tool,
                                   Map<String, Object> injectedArgs,
                                   String descriptionOverride,
                                   List<Runnable> onCallHooks,
                                   Consumer<String> onResultHook) {
        this.tool = tool;
        this.injectedArgs = injectedArgs != null ? injectedArgs : Map.of();
        this.descriptionOverride = descriptionOverride;
        this.onCallHooks = onCallHooks;
        this.onResultHook = onResultHook;
    }

    /** 简化构造：仅工具 + 注入参数 */
    public DelegatingToolCallback(AgentTool tool, Map<String, Object> injectedArgs) {
        this(tool, injectedArgs, null, null, null);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        String desc = descriptionOverride != null ? descriptionOverride : tool.description();
        return ToolDefinition.builder()
                .name(tool.name())
                .description(desc)
                .inputSchema(tool.parameterSchema())
                .build();
    }

    @Override
    public String call(String functionInput) {
        long startTime = System.currentTimeMillis();
        log.info("[TOOL_CALL] {} 开始执行, args={}", tool.name(), functionInput);
        try {
            // 解析 LLM 提供的参数
            Map<String, Object> llmArgs = MAPPER.readValue(functionInput,
                    new TypeReference<Map<String, Object>>() {});

            // 合并参数：LLM 参数在前，注入参数在后（注入参数不可被 LLM 覆盖）
            // 这样后端注入的 course_id 等系统参数不会被 LLM 推断的错误值覆盖
            Map<String, Object> merged = new java.util.LinkedHashMap<>(llmArgs);
            merged.putAll(injectedArgs);

            // 执行前钩子
            if (onCallHooks != null) {
                onCallHooks.forEach(Runnable::run);
            }

            // 执行工具
            String result = tool.execute(MAPPER.writeValueAsString(merged));

            // 执行后钩子
            if (onResultHook != null) {
                onResultHook.accept(result);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            // 检测结果中是否含 error 字段（工具内部异常的软失败）
            boolean hasError = result != null && result.contains("\"error\"");
            String preview = result != null
                    ? result.substring(0, Math.min(result.length(), 200))
                    : "null";
            if (hasError) {
                log.warn("[TOOL_RESULT] {} 执行完成(含错误), 耗时={}ms, 长度={}, 预览={}",
                        tool.name(), elapsed, result != null ? result.length() : 0, preview);
            } else {
                log.info("[TOOL_RESULT] {} 执行成功, 耗时={}ms, 长度={}, 预览={}",
                        tool.name(), elapsed, result != null ? result.length() : 0, preview);
            }

            return result;

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("[TOOL_ERROR] {} 执行失败, 耗时={}ms, error={}", tool.name(), elapsed, e.getMessage(), e);
            return "{\"error\":\"EXECUTION_ERROR\",\"message\":\"" +
                    e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}
