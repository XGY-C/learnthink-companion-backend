package com.learnthink.core.smart.tools;

import com.learnthink.core.agent.tools.ToolComposition;
import com.learnthink.core.agent.tools.ToolContext;
import com.learnthink.core.agent.tools.ToolGroup;
import com.learnthink.core.agent.tools.ToolRegistry;
import com.learnthink.core.smart.domain.ConceptStatus;
import com.learnthink.core.smart.domain.SmartContext;
import com.learnthink.core.smart.domain.SmartPhase;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Smart 模式工具组装策略。
 * <p>继承现有 {@link ToolComposition}，为 smart 模式组装工具集。
 * 按教学阶段动态裁剪工具，避免 20+ 工具全量暴露导致 LLM 选择准确率下降。</p>
 */
@Component
public class SmartToolComposition extends ToolComposition {

    private final ToolRegistry toolRegistry;

    /** 收敛后不再需要的教学工具 */
    private static final Set<String> TEACHING_TOOLS = Set.of(
        "assess_concept", "challenge_transfer",
        "update_concept_status", "generate_summary"
    );

    public SmartToolComposition(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Smart 模式组装全部可用工具。
     * <p>原则：尽可能多给工具，让 LLM 自由选择，但按教学阶段动态裁剪。</p>
     *
     * @param ctx      工具上下文（用户设置、课程/画像/沙箱可用性）
     * @param smartCtx Smart 模式上下文（概念状态、轮数等）
     * @return 可用工具名列表（有序、去重）
     */
    public List<String> composeSmartTools(ToolContext ctx, SmartContext smartCtx) {
        List<String> tools = new ArrayList<>();

        // 1. 复用现有基础工具组装逻辑（rag_retrieve、web_search、code_execution 等）
        tools.addAll(composeTools(ctx));

        // 2. 按教学阶段动态裁剪
        SmartPhase phase = determinePhase(smartCtx);

        switch (phase) {
            case EXPLORE -> {
                // 探索阶段：讲解概念、出题探测
                // 同时提供迁移和总结工具，允许 LLM 在同一轮中完成最后一个概念的掌握后立即调用
                tools.addAll(List.of(
                    "generate_svg",
                    "generate_chart",
                    "generate_mermaid",
                    "generate_mindmap",
                    "generate_html",
                    "generate_visualization",
                    "generate_threejs",
                    "generate_image",
                    "assess_concept",
                    "update_concept_status",
                    "challenge_transfer",
                    "generate_summary"
                ));
            }
            case TRANSFER -> {
                // 迁移阶段：所有概念已掌握，出变形题
                tools.addAll(List.of(
                    "generate_svg",
                    "generate_chart",
                    "generate_mermaid",
                    "generate_mindmap",
                    "generate_html",
                    "generate_visualization",
                    "generate_threejs",
                    "generate_image",
                    "challenge_transfer",
                    "update_concept_status",
                    "generate_summary"
                ));
            }
            case CONVERGED -> {
                // 已收敛：保留基础工具 + ask_user，移除教学工具
                tools.add("ask_user");
                tools.removeIf(TEACHING_TOOLS::contains);
            }
        }

        // 3. 加载 SMART_ONLY 分组的工具（如果注册表中有）
        List<String> smartOnlyTools = toolRegistry.listToolNames(ToolGroup.SMART_ONLY);
        if (phase != SmartPhase.CONVERGED) {
            tools.addAll(smartOnlyTools);
        }

        // 去重（保持顺序）
        return deduplicate(tools);
    }

    /**
     * 根据上下文判断当前教学阶段。
     */
    public SmartPhase determinePhase(SmartContext ctx) {
        if (ctx == null) return SmartPhase.EXPLORE;
        if (ctx.converged()) return SmartPhase.CONVERGED;
        if (ctx.concepts() == null || ctx.concepts().isEmpty()) return SmartPhase.EXPLORE;
        boolean allMastered = ctx.concepts().stream()
            .allMatch(c -> ctx.conceptStatus() != null
                && ctx.conceptStatus().getOrDefault(c.id(), ConceptStatus.UNVERIFIED)
                == ConceptStatus.MASTERED);
        return allMastered ? SmartPhase.TRANSFER : SmartPhase.EXPLORE;
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
