package com.learnthink.core.agent.impl;

import com.learnthink.core.agent.framework.AgentContext;
import com.learnthink.core.agent.framework.AgentResult;
import com.learnthink.core.agent.impl.generators.*;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates generation by delegating to type-specialized sub-agents.
 *
 * <pre>
 *   GeneratorAgent
 *     ├── DocumentGenerator  (explanatory articles)
 *     ├── ExerciseGenerator  (quizzes & practice problems)
 *     ├── ReadingGenerator   (extended reading lists)
 *     ├── CodeGenerator      (code examples & walkthroughs)
 *     ├── MindmapGenerator   (concept maps in JSON)
 *     └── VideoGenerator     (explanation videos via TTS + Manim rendering)
 * </pre>
 *
 * Each sub-agent has its own specialized prompt template, temperature, and output format.
 * This replaces the old monolithic approach where one agent handled all 5 types.
 */
@Component
public class GeneratorAgent {

    private static final Logger log = LoggerFactory.getLogger(GeneratorAgent.class);

    private final Map<String, TypeGenerator> generators;

    public GeneratorAgent(
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
     * Generate content for a specific resource type, delegating to the specialized sub-agent.
     *
     * @param reviewFeedback if regenerating after a review rejection, the reviewer's feedback
     */
    public AgentResult<ResourceGenerationState.GeneratedContent> generate(
        ResourceGenerationState.ResourcePlanItem planItem,
        List<ResourceGenerationState.SourceItem> typeSources,
        ResourceGenerationState.ProfileSummary profile,
        boolean forceLowConfidence,
        String reviewFeedback,
        AgentContext ctx) {

        log.info("=== GeneratorAgent START === type={}, title={}, feedback={}", 
                planItem.type(), planItem.title(), reviewFeedback != null ? "with feedback" : "initial");
        Instant start = Instant.now();
        String type = planItem.type();

        TypeGenerator gen = generators.get(type);
        if (gen == null) {
            log.error("No generator found for type: {}", type);
            return AgentResult.error("No generator for type: " + type);
        }

        log.info("Delegating to sub-generator: {}", gen.getClass().getSimpleName());
        ctx.observation().onDecision("GeneratorAgent", "delegate",
            "Delegating to " + gen.getClass().getSimpleName());

        try {
            var content = gen.generate(planItem, typeSources, profile, forceLowConfidence, reviewFeedback, ctx);
            long elapsed = java.time.Duration.between(start, Instant.now()).toMillis();
            log.info("Sub-generator completed in {}ms", elapsed);

            log.info("GeneratorAgent completed successfully for type: {}", type);
            return AgentResult.of(content, AgentResult.TokenUsage.ZERO, elapsed,
                Map.of("agent", "GeneratorAgent", "subAgent", gen.getClass().getSimpleName(),
                       "type", type, "regeneration", reviewFeedback != null));

        } catch (Exception e) {
            log.error("Generator failed for type={}: {}", type, e.getMessage(), e);
            ctx.observation().onError("GeneratorAgent/" + type, e);
            return AgentResult.error(type + " generation failed: " + e.getMessage());
        }
    }

    /**
     * Targeted revision based on review feedback. Delegates to the specialized sub-agent's revise().
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
                Map.of("agent", "GeneratorAgent", "subAgent", gen.getClass().getSimpleName(), "revised", true));
        } catch (Exception e) {
            return AgentResult.error(type + " revision failed: " + e.getMessage());
        }
    }
}
