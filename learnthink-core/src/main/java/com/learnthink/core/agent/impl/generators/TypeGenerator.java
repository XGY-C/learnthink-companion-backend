package com.learnthink.core.agent.impl.generators;

import com.learnthink.core.agent.runtime.AgentContext;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import java.util.List;

/**
 * 类型特化内容生成器接口
 * <p>每种资源类型（doc、quiz、reading、code、mindmap、video）都有各自的实现，
 * 包含专用的提示词模板、温度参数和输出格式处理。</p>
 */
public interface TypeGenerator {

    /** 此生成器处理的资源类型 */
    String type();

    /** 为此资源类型生成内容 */
    ResourceGenerationState.GeneratedContent generate(
        ResourceGenerationState.ResourcePlanItem planItem,
        List<ResourceGenerationState.SourceItem> typeSources,
        ResourceGenerationState.ProfileSummary profile,
        boolean forceLowConfidence,
        String reviewFeedback,
        AgentContext context
    );

    /**
     * 判断该资源类型是否需要证据来源进行内容生成
     * <p>返回 false 的类型将豁免 ContentReviewer 的来源覆盖率检查。
     * 当前默认 false——所有类型豁免来源要求。quiz 等需要来源的类型需单独覆盖为 true。</p>
     */
    default boolean requiresSourceCoverage() { return false; }

    /**
     * 基于审查反馈的定向修订
     * <p>与 generate() 从头创建不同，revise() 仅修改审查员指出的特定部分，
     * 保留已通过的部分。</p>
     */
    ResourceGenerationState.GeneratedContent revise(
        ResourceGenerationState.ResourcePlanItem planItem,
        List<ResourceGenerationState.SourceItem> typeSources,
        ResourceGenerationState.ProfileSummary profile,
        boolean forceLowConfidence,
        String reviewFeedback,
        ResourceGenerationState.GeneratedContent original,
        AgentContext context
    );
}
