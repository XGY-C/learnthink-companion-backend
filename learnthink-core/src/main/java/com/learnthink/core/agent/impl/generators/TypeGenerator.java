package com.learnthink.core.agent.impl.generators;

import com.learnthink.core.agent.framework.AgentContext;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import java.util.List;

/**
 * Contract for type-specialized content generators.
 * Each resource type (doc, quiz, reading, code, mindmap, video) has its own
 * implementation with specialized prompt templates, temperature, and output format handling.
 */
public interface TypeGenerator {

    /** The resource type this generator handles */
    String type();

    /** Generate content for this resource type */
    ResourceGenerationState.GeneratedContent generate(
        ResourceGenerationState.ResourcePlanItem planItem,
        List<ResourceGenerationState.SourceItem> typeSources,
        ResourceGenerationState.ProfileSummary profile,
        boolean forceLowConfidence,
        String reviewFeedback,
        AgentContext context
    );

    /**
     * Whether this resource type requires evidence sources for content generation.
     * Types that return false are exempt from source-coverage checks in ContentReviewer.
     * Default is true — only override for types where sources are not applicable
     * (e.g. reading lists, mindmaps, videos).
     */
    default boolean requiresSourceCoverage() { return true; }

    /**
     * Targeted revision based on review feedback.
     * Unlike generate() which creates from scratch, revise() modifies specific sections
     * identified by the reviewer, preserving approved sections.
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
