package com.learnthink.core.smart.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.*;

/**
 * 工具调用观察者。
 *
 * <p>包装所有 {@link ToolCallback}，在工具调用前后缓冲 SSE 事件：
 * <ul>
 *   <li>调用前：{@code agent.thought}（思考）+ {@code agent.tool_call}（工具调用开始）</li>
 *   <li>调用后：{@code agent.tool_result}（工具结果）+ {@code agent.visual}（可视化产物）</li>
 * </ul>
 *
 * <h3>机制说明</h3>
 * <p>Spring AI 的 {@code ChatModel} 内部自动处理多轮工具调用循环（Thought->Action->Observation）。
 * 循环过程中只认 {@code ToolCallback.call(String)} 接口--传入什么实例就调用什么实例。
 * 因此包装后的 ToolCallback 在每一轮工具调用时都会被触发，
 * 可以可靠地获取工具名、入参、执行结果。</p>
 *
 * <h3>事件缓冲</h3>
 * <p>工具事件不直接发送，而是写入共享 {@code eventBuffer}，
 * 由 {@code runReActLoop} 的 {@code flatMap} 统一 flush，保证事件顺序。</p>
 *
 * <h3>可视化工具的特殊处理</h3>
 * <p>对 {@code generate_svg} 等可视化工具，完整 code 通过 {@code agent.visual} 推前端渲染，
 * 回传 LLM 的 observation 只给摘要，不浪费上下文。</p>
 *
 * <h3>工具调用次数限制</h3>
 * <p>当单轮工具调用次数达到 {@code maxToolCalls} 时，后续工具调用将被拦截，
 * 返回错误 observation 给 LLM，提示其直接用文字回复。</p>
 */
public class ToolCallObserver {

    private static final Logger log = LoggerFactory.getLogger(ToolCallObserver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> VISUAL_TOOLS = Set.of(
        "generate_svg", "generate_chart", "generate_mermaid",
        "generate_mindmap", "generate_html", "generate_image",
        "generate_visualization", "generate_threejs"
    );

    private final List<SseEvent> eventBuffer;
    private final List<String> toolsUsed = Collections.synchronizedList(new ArrayList<>());
    private final int maxToolCalls;

    /** 持久化用：思考步骤记录（phase, content, tool, args, result） */
    private final List<Map<String, Object>> persistedThinkingSteps =
        Collections.synchronizedList(new ArrayList<>());

    public ToolCallObserver(List<SseEvent> eventBuffer, int maxToolCalls) {
        this.eventBuffer = eventBuffer;
        this.maxToolCalls = maxToolCalls;
    }

    /** 获取持久化用思考步骤列表 */
    public List<Map<String, Object>> getPersistedThinkingSteps() {
        return Collections.unmodifiableList(persistedThinkingSteps);
    }

    /**
     * 包装工具列表，注入观察逻辑。
     * 直接实现 ToolCallback 接口做透明代理。
     */
    public List<ToolCallback> wrap(List<ToolCallback> originals) {
        return originals.stream()
            .map(this::wrapSingle)
            .toList();
    }

    private ToolCallback wrapSingle(ToolCallback original) {
        String toolName = original.getToolDefinition().name();

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return original.getToolDefinition();
            }

            @Override
            public String call(String functionInput) {
                // 0. 检查工具调用次数是否超限
                if (toolsUsed.size() >= maxToolCalls) {
                    log.warn("Tool call limit exceeded ({}), skipping: {}", maxToolCalls, toolName);
                    bufferEvent(SseEvent.thought("ERROR",
                        "工具调用次数超限（" + maxToolCalls + "），跳过: " + toolName));
                    bufferEvent(SseEvent.toolCallStart(toolName, functionInput));
                    bufferEvent(SseEvent.toolResult(toolName, "已跳过：工具调用次数超限", false));
                    return "{\"error\":\"工具调用次数超限，请直接用文字回复学生\"}";
                }

                // ★ 诊断日志：工具调用开始
                log.info("[SMART-DIAG] === TOOL CALL START: {} ===", toolName);
                log.info("[SMART-DIAG] tool args (len={}): {}",
                    functionInput.length(),
                    functionInput.length() > 200 ? functionInput.substring(0, 200) + "..." : functionInput);

                // 1. 缓冲工具调用开始事件
                bufferEvent(SseEvent.toolCallStart(toolName, functionInput));

                // 持久化思考步骤：agent.tool_call -> done=false（后续 tool_result 会更新为 true）
                Map<String, Object> callStep = new LinkedHashMap<>();
                callStep.put("phase", "TOOL_CALL");
                callStep.put("label", "调用工具: " + toolName);
                callStep.put("icon", resolveToolIcon(toolName));
                callStep.put("content", summarizeArgs(functionInput));
                callStep.put("tool", toolName);
                callStep.put("done", false);
                persistedThinkingSteps.add(callStep);

                // 2. 执行工具
                long start = System.currentTimeMillis();
                String result;
                try {
                    result = original.call(functionInput);
                } catch (Exception e) {
                    bufferEvent(SseEvent.toolResult(toolName,
                        "error: " + e.getMessage(), false));
                    // 更新对应的 tool_call 步骤为完成（与 SSE 实时行为一致：tool_result 更新已有步骤）
                    callStep.put("done", true);
                    callStep.put("content", callStep.get("content") + " -> error: " + e.getMessage());
                    return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
                }
                long elapsed = System.currentTimeMillis() - start;

                // 3. 缓冲工具结果事件
                String resultSummary = summarizeResult(result);
                // web_search / rag_retrieve：提取结构化来源信息，供前端展示来源列表
                List<Map<String, Object>> searchSources = null;
                if ("web_search".equals(toolName)) {
                    searchSources = extractSearchSources(result);
                    bufferEvent(SseEvent.toolResultWithSources(toolName, resultSummary, true, searchSources));
                } else if ("rag_retrieve".equals(toolName)) {
                    searchSources = extractRagSources(result);
                    bufferEvent(SseEvent.toolResultWithSources(toolName, resultSummary, true, searchSources));
                } else {
                    bufferEvent(SseEvent.toolResult(toolName, resultSummary, true));
                }

                // ★ 诊断日志：工具调用完成
                log.info("[SMART-DIAG] === TOOL CALL END: {} ({}ms) ===", toolName, elapsed);
                log.info("[SMART-DIAG] tool result summary: {}",
                    resultSummary.length() > 200 ? resultSummary.substring(0, 200) + "..." : resultSummary);

                // 更新对应的 tool_call 步骤为完成（与 SSE 实时行为一致：tool_result 更新已有步骤，不创建新步骤）
                callStep.put("done", true);
                callStep.put("content", callStep.get("content") + " -> " + resultSummary);
                // web_search / rag_retrieve：持久化来源列表，前端加载历史时展示
                if (searchSources != null && !searchSources.isEmpty()) {
                    callStep.put("sources", searchSources);
                }

                // 4. 可视化工具：分离 observation 与 visual 事件
                String observation = result;
                if (isVisualTool(toolName)) {
                    // 4a. 先检查工具是否返回了 error，若是则不发 visual 事件
                    boolean hasError = false;
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsed = MAPPER.readValue(result, Map.class);
                        hasError = parsed.containsKey("error");
                    } catch (Exception ignored) {}

                    if (hasError) {
                        // 工具执行失败：不推 visual 事件，将错误原样回传 LLM
                        log.info("[SMART-DIAG] >>> VISUAL skipped (tool returned error): {}", toolName);
                        observation = result;
                    } else {
                    try {
                        // 4b. 完整 code 通过 agent.visual 推前端渲染
                        VisualResult normalized = normalizeVisualResult(toolName, result);
                        bufferEvent(SseEvent.visual(toolName, normalized.renderType,
                            normalized.code, normalized.description));

                        // ★ 诊断日志：可视化事件已缓冲
                        log.info("[SMART-DIAG] >>> VISUAL event buffered: tool={}, renderType={}, codeLen={}, desc={}",
                            toolName, normalized.renderType,
                            normalized.code != null ? normalized.code.length() : 0,
                            normalized.description);

                        // 4c. 回传 LLM 的 observation 只给摘要，不浪费上下文
                        observation = buildVisualObservation(normalized);

                        // ★ 诊断日志：返回给 LLM 的 observation
                        log.info("[SMART-DIAG] observation -> LLM: {}", observation);
                    } catch (Exception e) {
                        log.warn("Failed to normalize visual result for {}: {}", toolName, e.getMessage());
                        // 归一化失败时，仍发送 visual 事件（使用原始结果作为 code）
                        bufferEvent(SseEvent.visual(toolName, "raw", result, "可视化结果（原始格式）"));
                    }
                    }
                }

                toolsUsed.add(toolName);
                return observation;
            }
        };
    }

    /**
     * 构建给 LLM 的可视化摘要（非完整 code）。
     * LLM 只需知道"图已生成、什么图"，不需要完整 SVG/HTML 代码。
     */
    private String buildVisualObservation(VisualResult normalized) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                "type", normalized.renderType,
                "rendered", true,
                "summary", normalized.description != null ? normalized.description : ""
            ));
        } catch (Exception e) {
            return "{\"type\":\"" + normalized.renderType + "\",\"rendered\":true}";
        }
    }

    /** 将事件写入共享缓冲区，由 flatMap 统一 flush */
    private void bufferEvent(SseEvent event) {
        synchronized (eventBuffer) {
            eventBuffer.add(event);
        }
    }

    public List<String> getToolsUsed() {
        return Collections.unmodifiableList(toolsUsed);
    }

    private boolean isVisualTool(String name) {
        return VISUAL_TOOLS.contains(name);
    }

    /** 工具名称 -> 图标 */
    private static final Map<String, String> TOOL_ICONS = Map.ofEntries(
        Map.entry("rag_retrieve", "📚"),
        Map.entry("web_search", "🔍"),
        Map.entry("paper_search", "🔍"),
        Map.entry("generate_svg", "🎨"),
        Map.entry("generate_chart", "📊"),
        Map.entry("generate_mermaid", "📊"),
        Map.entry("generate_mindmap", "🧠"),
        Map.entry("generate_html", "🎨"),
        Map.entry("generate_visualization", "🎨"),
        Map.entry("generate_threejs", "🎨"),
        Map.entry("generate_image", "🎨"),
        Map.entry("assess_concept", "📝"),
        Map.entry("challenge_transfer", "📝"),
        Map.entry("update_concept_status", "📝"),
        Map.entry("generate_summary", "📝"),
        Map.entry("reason", "🤔"),
        Map.entry("brainstorm", "💡"),
        Map.entry("code_execution", "💻"),
        Map.entry("read_profile", "👤"),
        Map.entry("write_profile", "👤"),
        Map.entry("ask_user", "❓")
    );

    private static String resolveToolIcon(String toolName) {
        return TOOL_ICONS.getOrDefault(toolName, "🔧");
    }

    private String summarizeArgs(String args) {
        if (args == null || args.isEmpty()) return "(empty)";
        if (args.length() > 200) return args.substring(0, 200) + "...";
        return args;
    }

    private String summarizeResult(String result) {
        if (result == null || result.isEmpty()) return "(empty)";
        // 尝试提取关键字段
        try {
            Map<String, Object> parsed = MAPPER.readValue(result, new TypeReference<>() {});
            Object type = parsed.get("type");
            if (type != null) {
                Object desc = parsed.get("description");
                if (desc != null) {
                    return type + ": " + desc;
                }
                return type.toString();
            }
            Object error = parsed.get("error");
            if (error != null) {
                return "error: " + error;
            }
            // web_search 结果：提取搜索关键词和结果数
            Object query = parsed.get("query");
            Object count = parsed.get("count");
            if (query != null && count != null) {
                return "搜索\"" + query + "\"，找到 " + count + " 条结果";
            }
            // generate_image 结果：提取 prompt 和尺寸，避免泄露 base64
            Object imageBase64 = parsed.get("image_base64");
            if (imageBase64 != null) {
                return "image: " + parsed.getOrDefault("prompt", "")
                    + " (" + parsed.get("width") + "x" + parsed.get("height") + ")";
            }
        } catch (Exception ignored) {
            // 非 JSON，直接截断
        }
        return result.length() > 200 ? result.substring(0, 200) + "..." : result;
    }

    /** 从 web_search 工具结果中提取结构化来源列表 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractSearchSources(String result) {
        if (result == null || result.isEmpty()) return List.of();
        try {
            Map<String, Object> parsed = MAPPER.readValue(result, new TypeReference<>() {});
            Object resultsObj = parsed.get("results");
            if (resultsObj instanceof List<?> list) {
                List<Map<String, Object>> sources = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> itemMap) {
                        Map<String, Object> source = new LinkedHashMap<>();
                        source.put("title", toStr(itemMap.get("title")));
                        source.put("url", toStr(itemMap.get("url")));
                        source.put("snippet", toStr(itemMap.get("snippet")));
                        sources.add(source);
                    }
                }
                return sources;
            }
        } catch (Exception e) {
            log.warn("Failed to extract search sources: {}", e.getMessage());
        }
        return List.of();
    }

    /** 从 rag_retrieve 工具结果中提取结构化来源列表 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractRagSources(String result) {
        if (result == null || result.isEmpty()) return List.of();
        try {
            Map<String, Object> parsed = MAPPER.readValue(result, new TypeReference<>() {});
            Object sourcesObj = parsed.get("sources");
            if (sourcesObj instanceof List<?> list) {
                List<Map<String, Object>> sources = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> itemMap) {
                        Map<String, Object> source = new LinkedHashMap<>();
                        String bookTitle = toStr(itemMap.get("bookTitle"));
                        String chapterTitle = toStr(itemMap.get("chapterTitle"));
                        String title = (!bookTitle.isEmpty() ? "《" + bookTitle + "》" : "")
                            + (!chapterTitle.isEmpty() ? " " + chapterTitle : "");
                        if (title.isBlank()) title = toStr(itemMap.get("docId"));
                        source.put("title", title);
                        source.put("snippet", toStr(itemMap.get("quote")));
                        source.put("locator", toStr(itemMap.get("locator")));
                        source.put("relevance", itemMap.get("relevance"));
                        source.put("type", "rag");
                        sources.add(source);
                    }
                }
                return sources;
            }
        } catch (Exception e) {
            log.warn("Failed to extract rag sources: {}", e.getMessage());
        }
        return List.of();
    }

    /** 安全转字符串，null 返回空串 */
    private static String toStr(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    /**
     * 将不同工具的返回格式统一为 {type, code, description}。
     * 新增的 Smart 可视化工具已按统一格式返回；
     * 对现有工具（SvgGenerationTool、ImageGenerationTool）做字段适配。
     */
    private VisualResult normalizeVisualResult(String toolName, String rawResult) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = MAPPER.readValue(rawResult, Map.class);

        return switch (toolName) {
            case "generate_svg" -> {
                // 适配现有 SvgGenerationTool 返回格式
                String code = (String) parsed.getOrDefault("code", parsed.get("svg_code"));
                String desc = (String) parsed.getOrDefault("description", parsed.getOrDefault("prompt", ""));
                yield new VisualResult("svg", code != null ? code : "", desc);
            }
            case "generate_image" -> {
                String code = (String) parsed.getOrDefault("code", parsed.get("image_base64"));
                String desc = (String) parsed.getOrDefault("description", parsed.getOrDefault("prompt", ""));
                yield new VisualResult("image", code != null ? code : "", desc);
            }
            default -> {
                // 新增工具已按统一格式返回
                String type = (String) parsed.getOrDefault("type", "unknown");
                String code = (String) parsed.getOrDefault("code", "");
                String desc = (String) parsed.getOrDefault("description", "");
                yield new VisualResult(type, code, desc);
            }
        };
    }

    /** 可视化结果统一表示 */
    private record VisualResult(String renderType, String code, String description) {}
}
