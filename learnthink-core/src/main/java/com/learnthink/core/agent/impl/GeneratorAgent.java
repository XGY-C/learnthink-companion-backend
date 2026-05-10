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
 *     └── MindmapGenerator   (concept maps in JSON)
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
        MindmapGenerator mapGen
    ) {
        this.generators = Map.of(
            "document", docGen,
            "exercise", exGen,
            "reading",  readGen,
            "code",     codeGen,
            "mindmap",  mapGen
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

        Instant start = Instant.now();
        String type = planItem.type();

        TypeGenerator gen = generators.get(type);
        if (gen == null) {
            return AgentResult.error("No generator for type: " + type);
        }

        ctx.observation().onDecision("GeneratorAgent", "delegate",
            "Delegating to " + gen.getClass().getSimpleName());

        try {
            var content = gen.generate(planItem, typeSources, profile, forceLowConfidence, reviewFeedback);
            long elapsed = java.time.Duration.between(start, Instant.now()).toMillis();

            return AgentResult.of(content, AgentResult.TokenUsage.ZERO, elapsed,
                Map.of("agent", "GeneratorAgent", "subAgent", gen.getClass().getSimpleName(),
                       "type", type, "regeneration", reviewFeedback != null));

        } catch (Exception e) {
            log.error("Generator failed for type={}: {}", type, e.getMessage());
            ctx.observation().onError("GeneratorAgent/" + type, e);
            return AgentResult.error(type + " generation failed: " + e.getMessage());
        }
    }
}
