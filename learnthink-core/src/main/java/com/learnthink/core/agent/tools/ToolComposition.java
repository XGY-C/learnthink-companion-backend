package com.learnthink.core.agent.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具组合策略
 * <p>根据每轮对话的上下文条件，动态组装该轮可用的工具列表。</p>
 *
 * <h3>三层组合规则</h3>
 * <ol>
 *   <li><b>用户可切换工具</b> — 用户在设置页开关，如 web_search、reason、brainstorm</li>
 *   <li><b>上下文门控工具</b> — 满足条件自动挂载，如 hasCourse → rag_retrieve</li>
 *   <li><b>始终挂载工具</b> — 如 ask_user、write_profile</li>
 * </ol>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * ToolContext ctx = ToolContext.builder()
 *     .userToggledTools(List.of("web_search", "reason"))
 *     .hasCourse(true)
 *     .hasProfile(true)
 *     .hasJudge0(true)
 *     .build();
 *
 * List<String> enabled = composition.composeTools(ctx);
 * }</pre>
 */
public class ToolComposition {

    /** 用户可切换的工具（设置页可见） */
    public static final List<String> USER_TOGGLEABLE_TOOLS = List.of(
            "web_search",
            "reason",
            "brainstorm",
            "paper_search"
    );

    /** 始终挂载的工具 */
    public static final List<String> ALWAYS_ON_TOOLS = List.of(
            "ask_user",
            "write_profile"
    );

    /**
     * 根据上下文组装可用工具列表
     *
     * @param ctx 工具上下文
     * @return 可用工具名列表（有序、去重）
     */
    public List<String> composeTools(ToolContext ctx) {
        List<String> composed = new ArrayList<>();

        // 1. 用户切换的工具（过滤掉未在 USER_TOGGLEABLE 中的）
        if (ctx.userToggledTools() != null) {
            for (String tool : ctx.userToggledTools()) {
                if (USER_TOGGLEABLE_TOOLS.contains(tool)) {
                    composed.add(tool);
                }
            }
        }

        // 2. 上下文门控工具
        if (ctx.hasCourse()) {
            composed.add("rag_retrieve");
            composed.add("get_book_info");
        }
        if (ctx.hasProfile()) {
            composed.add("read_profile");
        }
        if (ctx.hasLearningPath()) {
            composed.add("read_learning_path");
        }
        if (ctx.hasJudge0()) {
            composed.add("code_execution");
        }

        // 3. 始终挂载
        composed.addAll(ALWAYS_ON_TOOLS);

        // 去重（保持顺序）
        return deduplicate(composed);
    }

    /**
     * 获取默认用户可切换工具列表（全部开启）
     */
    public List<String> defaultUserTools() {
        return new ArrayList<>(USER_TOGGLEABLE_TOOLS);
    }

    /** 有序去重 */
    private List<String> deduplicate(List<String> list) {
        List<String> result = new ArrayList<>();
        for (String item : list) {
            if (!result.contains(item)) {
                result.add(item);
            }
        }
        return result;
    }
}
