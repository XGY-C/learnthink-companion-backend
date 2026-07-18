package com.learnthink.core.agent.tools;

import com.learnthink.core.agent.runtime.AgentTool;
import com.learnthink.core.agent.runtime.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表
 * <p>统一管理所有已注册的 {@link AgentTool} 实例，提供查找、列举和批量获取能力。</p>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>线程安全：使用 {@link ConcurrentHashMap}，支持运行时动态注册/注销</li>
 *   <li>工具别名：通过 {@link #registerAlias} 支持一个工具多个调用名</li>
 *   <li>工具分组：通过 {@link #register(AgentTool, ToolGroup)} 记录工具分类</li>
 *   <li>现有工具（RagTool、BookInfoTool 等）可注册进来，也可保持原有调用方式</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @Autowired ToolRegistry registry;
 *
 * // 注册工具
 * registry.register(new CodeExecutionTool(judge0Client), ToolGroup.CONTEXT_GATED);
 *
 * // 按名称查找
 * AgentTool tool = registry.get("code_execution");
 *
 * // 批量获取
 * List<AgentTool> tools = registry.getEnabled(List.of("rag_retrieve", "code_execution"));
 * }</pre>
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** 工具名 → 工具实例 */
    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();

    /** 工具名 → 分组 */
    private final Map<String, ToolGroup> groups = new ConcurrentHashMap<>();

    /** 别名 → 实际工具名 */
    private final Map<String, String> aliases = new ConcurrentHashMap<>();

    /**
     * 注册工具
     *
     * @param tool  工具实例
     * @param group 工具分组
     */
    public void register(AgentTool tool, ToolGroup group) {
        String name = tool.name();
        tools.put(name, tool);
        groups.put(name, group);
        log.info("Registered tool: name={}, group={}", name, group);
    }

    /**
     * 注册工具（默认分组 CONTEXT_GATED）
     */
    public void register(AgentTool tool) {
        register(tool, ToolGroup.CONTEXT_GATED);
    }

    /**
     * 注销工具
     */
    public void unregister(String name) {
        tools.remove(name);
        groups.remove(name);
        // 清理指向该工具的别名
        aliases.entrySet().removeIf(e -> e.getValue().equals(name));
        log.info("Unregistered tool: name={}", name);
    }

    /**
     * 注册别名
     *
     * @param alias    别名
     * @param toolName 实际工具名
     */
    public void registerAlias(String alias, String toolName) {
        aliases.put(alias, toolName);
        log.debug("Registered alias: {} -> {}", alias, toolName);
    }

    /**
     * 解析名称（处理别名），返回实际工具名
     */
    private String resolveName(String name) {
        return aliases.getOrDefault(name, name);
    }

    /**
     * 按名称查找工具
     *
     * @return 工具实例，不存在返回 null
     */
    public AgentTool get(String name) {
        return tools.get(resolveName(name));
    }

    /**
     * 获取所有已注册工具
     */
    public List<AgentTool> getAll() {
        return new ArrayList<>(tools.values());
    }

    /**
     * 获取指定分组的所有工具
     */
    public List<AgentTool> getByGroup(ToolGroup group) {
        return tools.entrySet().stream()
                .filter(e -> group.equals(groups.get(e.getKey())))
                .map(Map.Entry::getValue)
                .toList();
    }

    /**
     * 批量获取工具实例（跳过未知工具，去重）
     *
     * @param names 工具名列表
     * @return 工具实例列表（保持输入顺序）
     */
    public List<AgentTool> getEnabled(List<String> names) {
        List<AgentTool> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            String resolved = resolveName(name);
            if (seen.contains(resolved)) continue;
            AgentTool tool = tools.get(resolved);
            if (tool != null) {
                result.add(tool);
                seen.add(resolved);
            } else {
                log.warn("Tool not found in registry: {}", name);
            }
        }
        return result;
    }

    /**
     * 批量获取工具定义（用于生成 LLM 函数调用 schema）
     */
    public List<ToolDefinition> getDefinitions(List<String> names) {
        return getEnabled(names).stream()
                .map(t -> ToolDefinition.of(t.name(), t.description(), t.parameterSchema()))
                .toList();
    }

    /**
     * 列出所有已注册的工具名
     */
    public List<String> listToolNames() {
        return new ArrayList<>(tools.keySet());
    }

    /**
     * 列出指定分组的工具名
     */
    public List<String> listToolNames(ToolGroup group) {
        return tools.entrySet().stream()
                .filter(e -> group.equals(groups.get(e.getKey())))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 是否已注册某个工具
     */
    public boolean contains(String name) {
        return tools.containsKey(resolveName(name));
    }

    /**
     * 获取工具分组
     */
    public ToolGroup getGroup(String name) {
        return groups.get(resolveName(name));
    }
}
