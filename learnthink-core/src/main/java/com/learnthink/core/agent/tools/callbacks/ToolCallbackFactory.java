package com.learnthink.core.agent.tools.callbacks;

import com.learnthink.core.agent.runtime.AgentTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * ToolCallback 统一工厂
 * <p>将 {@link AgentTool} 包装为 Spring AI {@link ToolCallback} 的统一入口，
 * 替代为每个工具手写 Callback 类的模式。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @Autowired ToolCallbackFactory factory;
 *
 * // 单个包装
 * ToolCallback cb = factory.wrap(ragTool, Map.of("course_id", courseId));
 *
 * // 批量包装
 * List<ToolCallback> cbs = factory.wrapAll(
 *     List.of(ragTool, bookInfoTool, codeExecTool),
 *     Map.of("course_id", courseId, "user_id", userId)
 * );
 *
 * // 带 SSE 钩子
 * ToolCallback cb = factory.wrap(ragTool, Map.of("course_id", courseId),
 *     List.of(() -> emitter.send(event("retrieve"))),
 *     result -> emitter.send(event("rag_done"))
 * );
 * }</pre>
 */
@Component
public class ToolCallbackFactory {

    /**
     * 包装单个工具（简单模式）
     *
     * @param tool         工具实例
     * @param injectedArgs 服务端注入的参数（如 course_id）
     * @return Spring AI ToolCallback
     */
    public ToolCallback wrap(AgentTool tool, Map<String, Object> injectedArgs) {
        return new DelegatingToolCallback(tool, injectedArgs);
    }

    /**
     * 包装单个工具（带钩子）
     *
     * @param tool          工具实例
     * @param injectedArgs  服务端注入的参数
     * @param onCallHooks   执行前钩子
     * @param onResultHook  执行后钩子
     * @return Spring AI ToolCallback
     */
    public ToolCallback wrap(AgentTool tool,
                              Map<String, Object> injectedArgs,
                              List<Runnable> onCallHooks,
                              Consumer<String> onResultHook) {
        return new DelegatingToolCallback(tool, injectedArgs, null, onCallHooks, onResultHook);
    }

    /**
     * 包装单个工具（带描述覆盖）
     *
     * @param tool               工具实例
     * @param injectedArgs       服务端注入的参数
     * @param descriptionOverride 自定义工具描述
     * @return Spring AI ToolCallback
     */
    public ToolCallback wrap(AgentTool tool,
                              Map<String, Object> injectedArgs,
                              String descriptionOverride) {
        return new DelegatingToolCallback(tool, injectedArgs, descriptionOverride, null, null);
    }

    /**
     * 批量包装工具（简单模式，共享注入参数）
     *
     * @param tools        工具列表
     * @param injectedArgs 服务端注入的参数（所有工具共享）
     * @return Spring AI ToolCallback 列表
     */
    public List<ToolCallback> wrapAll(List<AgentTool> tools, Map<String, Object> injectedArgs) {
        return tools.stream()
                .map(t -> wrap(t, injectedArgs))
                .toList();
    }
}
