package com.learnthink.core.agent.impl.generators;

import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import java.util.List;

/**
 * Contract for type-specialized content generators.
 * Each resource type (doc, quiz, reading, code, mindmap) has its own implementation
 * with specialized prompt templates, temperature, and output format handling.
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
        String reviewFeedback
    );
}
