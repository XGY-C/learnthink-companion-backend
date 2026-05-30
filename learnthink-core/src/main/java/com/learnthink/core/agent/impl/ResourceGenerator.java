package com.learnthink.core.agent.impl;

import com.learnthink.core.agent.runtime.AgentContext;
import com.learnthink.core.agent.runtime.AgentResult;
import com.learnthink.core.agent.impl.generators.*;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 资源生成编排器——将生成任务委派给类型特化的子生成器
 *
 * <pre>
 *   ResourceGenerator
 *     ├── DocumentGenerator   （讲解性文章）
 *     ├── ExerciseGenerator   （习题与练习）
 *     ├── ReadingGenerator    （扩展阅读列表）
 *     ├── CodeGenerator       （代码示例与演练）
 *     ├── MindmapGenerator    （JSON 格式概念图）
 *     └── VideoGenerator      （讲解视频，通过 TTS + Manim 渲染）
 * </pre>
 */
@Component
public class ResourceGenerator {

    private static final Logger log = LoggerFactory.getLogger(ResourceGenerator.class);

    private final Map<String, TypeGenerator> generators;

    public ResourceGenerator(
        DocumentGenerator docGen,
        ExerciseGenerator exGen,
        ReadingGenerator readGen,
        CodeGenerator codeGen,
        MindmapGenerator mapGen,
        VideoGenerator videoGen
    ) {
        this.generators = Map.of(
            "doc", docGen,
            "quiz", exGen,
            "reading",  readGen,
            "code",     codeGen,
            "mindmap",  mapGen,
            "video", videoGen
        );
    }

    /**
     * 为指定资源类型生成内容，委派给特化的子生成器
     *
     * @param reviewFeedback 审查拒绝后重新生成时的审查反馈
     */
    public AgentResult<ResourceGenerationState.GeneratedContent> generate(
        ResourceGenerationState.ResourcePlanItem planItem,
        List<ResourceGenerationState.SourceItem> typeSources,
        ResourceGenerationState.ProfileSummary profile,
        boolean forceLowConfidence,
        String reviewFeedback,
        AgentContext ctx) {

        log.info("=== ResourceGenerator START === type={}, title={}, feedback={}",
                planItem.type(), planItem.title(), reviewFeedback != null ? "with feedback" : "initial");
        Instant start = Instant.now();
        String type = planItem.type();

        TypeGenerator gen = generators.get(type);
        if (gen == null) {
            log.error("No generator found for type: {}", type);
            return AgentResult.error("No generator for type: " + type);
        }

        log.info("Delegating to sub-generator: {}", gen.getClass().getSimpleName());
        ctx.observation().onDecision("ResourceGenerator", "delegate",
            "Delegating to " + gen.getClass().getSimpleName());

        try {
            var content = gen.generate(planItem, typeSources, profile, forceLowConfidence, reviewFeedback, ctx);
            long elapsed = java.time.Duration.between(start, Instant.now()).toMillis();
            log.info("Sub-generator completed in {}ms", elapsed);

            log.info("ResourceGenerator completed successfully for type: {}", type);
            return AgentResult.of(content, AgentResult.TokenUsage.ZERO, elapsed,
                Map.of("agent", "ResourceGenerator", "subAgent", gen.getClass().getSimpleName(),
                       "type", type, "regeneration", reviewFeedback != null));

        } catch (Exception e) {
            log.error("Generator failed for type={}: {}", type, e.getMessage(), e);
            ctx.observation().onError("ResourceGenerator/" + type, e);
            return AgentResult.error(type + " generation failed: " + e.getMessage());
        }
    }

    /**
     * 基于审查反馈进行定向修订。委派给特化子生成器的 revise() 方法。
     */
    public AgentResult<ResourceGenerationState.GeneratedContent> revise(
        ResourceGenerationState.ResourcePlanItem planItem,
        List<ResourceGenerationState.SourceItem> typeSources,
        ResourceGenerationState.ProfileSummary profile,
        boolean forceLowConfidence,
        String reviewFeedback,
        ResourceGenerationState.GeneratedContent original,
        AgentContext ctx) {

        String type = planItem.type();
        TypeGenerator gen = generators.get(type);
        if (gen == null) {
            return AgentResult.error("No generator for type: " + type);
        }
        try {
            var content = gen.revise(planItem, typeSources, profile, forceLowConfidence, reviewFeedback, original, ctx);
            return AgentResult.of(content, AgentResult.TokenUsage.ZERO, 0,
                Map.of("agent", "ResourceGenerator", "subAgent", gen.getClass().getSimpleName(), "revised", true));
        } catch (Exception e) {
            return AgentResult.error(type + " revision failed: " + e.getMessage());
        }
    }

    /**
     * 判断指定资源类型是否需要证据来源
     * 委派给 {@link TypeGenerator#requiresSourceCoverage()}。
     */
    boolean requiresSourceCoverage(String type) {
        TypeGenerator gen = generators.get(type);
        return gen != null && gen.requiresSourceCoverage();
    }
}
