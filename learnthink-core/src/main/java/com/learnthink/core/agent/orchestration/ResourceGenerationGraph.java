package com.learnthink.core.agent.orchestration;

import com.learnthink.core.agent.framework.AgentContext;
import com.learnthink.core.agent.framework.AgentResult;
import com.learnthink.core.agent.graph.StateGraph;
import com.learnthink.core.agent.graph.GraphRunner;
import com.learnthink.core.agent.impl.*;
import com.learnthink.core.service.TaskPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The resource generation pipeline expressed as a {@link StateGraph}.
 *
 * <h3>Graph topology (with feedback loops)</h3>
 * <pre>
 *   PROFILING → RETRIEVING → PLANNING → GENERATING → REVIEWING → PUBLISHING
 *                                                     ↑        ↓
 *                                                     └────────┘ regenerate rejected
 *                                       ↑                      │
 *                                       └──────────────────────┘ replan on too many failures
 * </pre>
 *
 * <h3>Key improvements over the old linear pipeline:</h3>
 * <ul>
 *   <li><b>Feedback loop: Reviewer → Generator</b> — rejected resources are regenerated
 *       with review feedback, up to MAX_REGENERATE times.</li>
 *   <li><b>Feedback loop: Reviewer → Planner</b> — if too many resources fail review,
 *       the planner re-plans instead of blindly retrying.</li>
 *   <li><b>Conditional routing</b> — each stage can route to different next stages based
 *       on actual output quality.</li>
 *   <li><b>Granular tracing</b> — every agent decision is recorded in the graph observer.</li>
 * </ul>
 */
public class ResourceGenerationGraph {

    private static final Logger log = LoggerFactory.getLogger(ResourceGenerationGraph.class);

    static final int MAX_REGENERATE = 2;
    static final int MAX_REPLAN = 1;
    static final int REPLAN_THRESHOLD = 3; // replan if >=3 types fail review

    private final ProfileAgent profileAgent;
    private final RetrieverAgent retrieverAgent;
    private final PlannerAgent plannerAgent;
    private final GeneratorAgent generatorAgent;
    private final ReviewerAgent reviewerAgent;
    private final Publisher publisher;
    private final TaskPersistenceService persistenceService;

    public ResourceGenerationGraph(
        ProfileAgent profileAgent,
        RetrieverAgent retrieverAgent,
        PlannerAgent plannerAgent,
        GeneratorAgent generatorAgent,
        ReviewerAgent reviewerAgent,
        Publisher publisher,
        TaskPersistenceService persistenceService
    ) {
        this.profileAgent = profileAgent;
        this.retrieverAgent = retrieverAgent;
        this.plannerAgent = plannerAgent;
        this.generatorAgent = generatorAgent;
        this.reviewerAgent = reviewerAgent;
        this.publisher = publisher;
        this.persistenceService = persistenceService;
    }

    /**
     * Build the state graph with all nodes, edges, and conditional routing.
     */
    public GraphRunner<ResourceGenerationState> build() {
        return StateGraph.<ResourceGenerationState>create(ResourceGenerationState.class)
            // -- Nodes --
            .addNode("PROFILING", this::doProfiling, "Extract learning profile summary")
            .addNode("RETRIEVING", this::doRetrieving, "Retrieve evidence from knowledge base")
            .addNode("PLANNING", this::doPlanning, "Plan resource composition and outline")
            .addNode("GENERATING", this::doGenerating, "Generate all resource types")
            .addNode("REVIEWING", this::doReviewing, "Review generated content for quality and safety")
            .addNode("PUBLISHING", this::doPublishing, "Persist approved resources")
            .addNode("FALLBACK", this::doFallback, "Generate with limited evidence (degraded mode)")

            // -- Edges (unconditional) --
            .addEdge("PROFILING", "RETRIEVING")
            .addEdge("PLANNING", "GENERATING")
            .addEdge("FALLBACK", "PUBLISHING")

            // -- Conditional edges --
            .addConditionalEdge("RETRIEVING", this::routeAfterRetrieving)
            .addConditionalEdge("GENERATING", this::routeAfterGenerating)
            .addConditionalEdge("REVIEWING", this::routeAfterReviewing)

            .setEntryPoint("PROFILING")
            .setMaxCycles(4) // allow feedback loops
            .compile();
    }

    // === Node implementations ===

    private ResourceGenerationState doProfiling(ResourceGenerationState s) {
        advance(s, "PROFILING", 0, "Analyzing learning profile...");
        AgentContext ctx = buildContext(s);

        var result = profileAgent.summarize(s.userId, s.courseId, s.profileVersion, ctx);
        if (!result.success()) {
            fail(s, "PROFILE_NOT_READY", result.errorMessage(), false);
            return s;
        }
        s.profileSummary = result.output();
        advance(s, "PROFILING", 15, "Profile analysis complete (" + s.profileSummary.dimensionCount() + " dimensions)");
        return s;
    }

    private ResourceGenerationState doRetrieving(ResourceGenerationState s) {
        advance(s, "RETRIEVING", 15, "Retrieving course evidence...");
        AgentContext ctx = buildContext(s);

        Map<String, List<ResourceGenerationState.SourceItem>> evidenceByType = new HashMap<>();
        List<ResourceGenerationState.SourceItem> merged = new ArrayList<>();
        int completed = 0;
        int totalSources = 0;

        for (String resourceType : s.resourceTypes) {
            var result = retrieverAgent.retrieve(
                s.courseId, s.topic, resourceType, ctx);
            if (!result.success()) {
                if ("KB_NOT_READY".equals(s.errorCode)) {
                    return s; // router will send to FALLBACK
                }
                s.forceLowConfidence = true;
                continue;
            }
            var pack = result.output();
            if (pack != null && !pack.isEmpty()) {
                evidenceByType.put(resourceType, pack);
                merged.addAll(pack);
                totalSources += pack.size();
            }
            completed++;
            int pct = 15 + (completed * 20 / s.resourceTypes.size());
            advance(s, "RETRIEVING", pct,
                "Retrieving evidence... (" + completed + "/" + s.resourceTypes.size() + ")");
        }

        // Deduplicate
        s.mergedSources = merged.stream()
            .distinct()
            .collect(Collectors.toList());
        s.evidenceByType = evidenceByType;
        s.totalSources = totalSources;
        advance(s, "RETRIEVING", 35, "Retrieved " + s.totalSources + " evidence chunks");
        return s;
    }

    private ResourceGenerationState doPlanning(ResourceGenerationState s) {
        advance(s, "PLANNING", 35, "Planning resource pack...");
        AgentContext ctx = buildContext(s);

        String feedback = s.planFeedback;

        var result = plannerAgent.plan(
            s.profileSummary, s.mergedSources, s.topic, s.resourceTypes, feedback, ctx);
        if (!result.success()) {
            fail(s, "PLAN_ERROR", result.errorMessage(), false);
            return s;
        }
        s.resourcePlan = result.output();
        s.planRetryCount++;
        advance(s, "PLANNING", 55,
            "Planned " + s.resourcePlan.items().size() + " resources");
        return s;
    }

    private ResourceGenerationState doGenerating(ResourceGenerationState s) {
        AgentContext ctx = buildContext(s);
        ctx.put("forceLowConfidence", s.forceLowConfidence);

        // Determine which types to generate (on regenerate, only redo failed ones)
        // Data is stored in state (not AgentContext) to survive node revisits
        boolean isRegeneration = s.regenerateTypes != null && !s.regenerateTypes.isEmpty();
        List<ResourceGenerationState.ResourcePlanItem> itemsToGenerate;
        if (isRegeneration) {
            itemsToGenerate = s.resourcePlan.items().stream()
                .filter(i -> s.regenerateTypes.contains(i.type()))
                .toList();
            log.info("Regenerating {} types: {}", s.regenerateTypes.size(), s.regenerateTypes);
        } else {
            itemsToGenerate = s.resourcePlan.items();
        }

        if (itemsToGenerate.isEmpty()) {
            advance(s, "GENERATING", 85, "No resources to generate");
            return s;
        }

        int genCompleted = 0;
        for (ResourceGenerationState.ResourcePlanItem item : itemsToGenerate) {
            advance(s, "GENERATING",
                55 + (genCompleted * 30 / itemsToGenerate.size()),
                "Generating: " + item.title());

            // Delegate to type-specialized sub-agent via GeneratorAgent
            var typeSources = s.evidenceByType != null
                ? s.evidenceByType.getOrDefault(item.type(), List.of())
                : List.<ResourceGenerationState.SourceItem>of();
            String reviewFeedback = s.reviewFeedbackByType != null
                ? s.reviewFeedbackByType.get(item.type()) : null;

            var result = generatorAgent.generate(item, typeSources, s.profileSummary,
                s.forceLowConfidence, reviewFeedback, ctx);
            if (result.success()) {
                s.artifacts.put(item.type(), result.output());
                // Fire resource-level ready event so frontend can update individual cards
                resourceReady(s, item.type(), result.output().title(),
                    result.output().confidence(),
                    result.output().sources() != null ? result.output().sources().size() : 0);
            } else {
                log.warn("Generation failed for type={}: {}", item.type(), result.errorMessage());
                s.failedTypes.add(item.type());
            }
            genCompleted++;
        }

        advance(s, "GENERATING", 85,
            "Resources generated (" + s.artifacts.size() + "/" + itemsToGenerate.size() + " successful)");
        return s;
    }

    private ResourceGenerationState doReviewing(ResourceGenerationState s) {
        advance(s, "REVIEWING", 85, "Reviewing generated content...");
        AgentContext ctx = buildContext(s);

        s.reviewResults.clear();
        int reviewCompleted = 0;
        int totalToReview = s.artifacts.size();

        for (var entry : s.artifacts.entrySet()) {
            String type = entry.getKey();
            var content = entry.getValue();
            var typeSources = s.evidenceByType != null
                ? s.evidenceByType.getOrDefault(type, List.of())
                : List.<ResourceGenerationState.SourceItem>of();

            advance(s, "REVIEWING", 85 + (reviewCompleted * 10 / totalToReview),
                "Reviewing: " + type);

            var result = reviewerAgent.review(content, typeSources, type, s.forceLowConfidence, ctx);
            s.reviewResults.put(type, result.output());

            // Log decision and broadcast review result
            ctx.observation().onDecision("ReviewerAgent",
                result.output().action().name(),
                result.output().reviewSummary());

            // Fire review-flag event as a dedicated SSE event type if not approved
            if (result.output().action() != ResourceGenerationState.ReviewAction.PUBLISH) {
                if (s.eventBroadcaster != null) {
                    s.eventBroadcaster.reviewFlag(s.taskId, type,
                        result.output().action().name(),
                        result.output().confidence(),
                        result.output().citationCoverage());
                }
            }

            // Persist review record to MySQL
            if (persistenceService != null) {
                try {
                    persistenceService.recordReviewFlag(s.taskId, type,
                        result.output().action().name(),
                        result.output().confidence(),
                        result.output().citationCoverage());
                    // Determine resourceItemId from artifacts or use type as fallback
                    String resourceItemId = content.title() != null ? content.title() : type;
                    persistenceService.recordReview(
                        resourceItemId, s.packId != null ? s.packId : s.taskId,
                        s.taskId,
                        result.output().action() == ResourceGenerationState.ReviewAction.PUBLISH ? "approved" : "rejected",
                        result.output().reviewSummary(),
                        result.output().citationCoverage());
                } catch (Exception e) {
                    log.warn("Failed to persist review record: {}", e.getMessage());
                }
            }

            reviewCompleted++;
        }

        s.reviewRetryCount++;
        advance(s, "REVIEWING", 95, "Review complete (" + totalToReview + " resources)");
        return s;
    }

    private ResourceGenerationState doPublishing(ResourceGenerationState s) {
        advance(s, "PUBLISHING", 95, "Publishing resources...");
        AgentContext ctx = buildContext(s);

        for (var entry : s.artifacts.entrySet()) {
            String type = entry.getKey();
            var content = entry.getValue();
            var review = s.reviewResults.get(type);

            if (review != null && review.action() == ResourceGenerationState.ReviewAction.REJECT_PERMANENT) {
                s.failedTypes.add(type);
                continue;
            }

            var result = publisher.publish(s.taskId, type, content, review, ctx);
            if (result.success()) {
                s.publishedTypes.add(type);
            } else {
                s.failedTypes.add(type);
            }
        }

        if (s.packId == null) {
            s.packId = UUID.randomUUID().toString();
        }

        s.status = "SUCCEEDED";
        s.finishedAt = java.time.Instant.now();
        advance(s, "PUBLISHING", 100,
            "Published " + s.publishedTypes.size() + " resources" +
            (s.failedTypes.isEmpty() ? "" : ", " + s.failedTypes.size() + " failed"));
        return s;
    }

    private ResourceGenerationState doFallback(ResourceGenerationState s) {
        advance(s, "FALLBACK", 35, "Knowledge base unavailable — generating with limited guidance...");
        s.forceLowConfidence = true;
        s.mergedSources = List.of();
        s.evidenceByType = Map.of();

        // Skip planning — go straight to generation with minimal structure
        s.resourcePlan = new ResourceGenerationState.ResourcePlan(
            "# " + s.topic,
            s.resourceTypes.stream()
                .map(t -> new ResourceGenerationState.ResourcePlanItem(
                    t, s.topic + " - " + t, "medium", 15, "markdown",
                    List.of(), " Generated without course evidence (KB unavailable)"))
                .toList(),
            List.of("KB not available — use general knowledge"),
            List.of()
        );

        s._nextRoute = "GENERATING";
        return s;
    }

    // === Conditional routers ===

    private String routeAfterRetrieving(ResourceGenerationState s) {
        if (s.forceLowConfidence && s.totalSources == 0) {
            log.info("KB not ready — routing to FALLBACK");
            return "FALLBACK";
        }
        return "PLANNING";
    }

    private String routeAfterGenerating(ResourceGenerationState s) {
        if (s.artifacts.isEmpty() && !s.failedTypes.isEmpty()) {
            log.error("All generation failed");
            s.status = "FAILED";
            s.errorCode = "GENERATE_ALL_FAILED";
            return null; // terminal
        }
        return "REVIEWING";
    }

    private String routeAfterReviewing(ResourceGenerationState s) {
        // Count rejections
        Set<String> rejectedTypes = new HashSet<>();
        Set<String> retryPermanentTypes = new HashSet<>();
        StringBuilder feedbackBuilder = new StringBuilder();

        for (var entry : s.reviewResults.entrySet()) {
            String type = entry.getKey();
            var result = entry.getValue();
            if (result.action() == ResourceGenerationState.ReviewAction.RETRY) {
                rejectedTypes.add(type);
                feedbackBuilder.append("[").append(type).append("]: ").append(result.reviewSummary()).append("; ");
            } else if (result.action() == ResourceGenerationState.ReviewAction.REJECT_PERMANENT) {
                retryPermanentTypes.add(type);
                s.failedTypes.add(type);
            }
        }

        // Permanent rejections — remove from artifacts
        retryPermanentTypes.forEach(s.artifacts::remove);

        if (rejectedTypes.isEmpty()) {
            // All approved → continue to publishing
            return "PUBLISHING";
        }

        String feedback = feedbackBuilder.toString();
        log.info("Review feedback: {} rejected types — {}", rejectedTypes.size(), feedback);

        // Decision: replan or regenerate?
        if (rejectedTypes.size() >= REPLAN_THRESHOLD && s.planRetryCount <= MAX_REPLAN) {
            // Too many rejections — replan with feedback
            log.info("Replanning due to {} rejected types", rejectedTypes.size());
            s.planFeedback = "Previous plan had issues: " + feedback +
                ". Please restructure the outline and adjust difficulty/scope for the rejected types.";
            // Clear regeneration state since we're replanning
            s.regenerateTypes = null;
            s.reviewFeedbackByType = null;
            return "PLANNING";
        }

        if (s.reviewRetryCount <= MAX_REGENERATE) {
            // Regenerate just the rejected types with review feedback
            log.info("Regenerating {} rejected types (attempt {}/{})",
                rejectedTypes.size(), s.reviewRetryCount, MAX_REGENERATE);
            s.regenerateTypes = rejectedTypes;
            s.reviewFeedbackByType = new java.util.HashMap<>();
            for (String type : rejectedTypes) {
                var review = s.reviewResults.get(type);
                if (review != null) {
                    s.reviewFeedbackByType.put(type, review.reviewSummary());
                }
            }
            return "GENERATING";
        }

        // Max retries exceeded — proceed with failures
        log.warn("Max regenerate/replan exceeded — proceeding with {} failed types", rejectedTypes.size());
        s.failedTypes.addAll(rejectedTypes);
        rejectedTypes.forEach(s.artifacts::remove);
        return "PUBLISHING";
    }

    // === Helpers ===

    private void advance(ResourceGenerationState s, String stage, int percent, String message) {
        s.stage = stage;
        s.percent = percent;
        s.message = message;
        s.status = "RUNNING";

        // Persist to MySQL for traceability
        if (persistenceService != null) {
            try {
                persistenceService.updateTaskStage(s.taskId, stage, percent, "RUNNING");
                persistenceService.recordStageEvent(s.taskId, stage, percent, message, null);
            } catch (Exception e) {
                log.warn("Failed to persist stage event: {}", e.getMessage());
            }
        }

        // Fire real-time progress via the hook (wired by TaskGraphObserver)
        if (s.progressHook != null) {
            s.progressHook.onProgress(stage, percent, message, java.util.Map.of("timestamp", System.currentTimeMillis()));
        }
    }

    /** Broadcast individual resource ready event as a dedicated SSE event type */
    private void resourceReady(ResourceGenerationState s, String type, String title, String confidence, int sourceCount) {
        if (s.eventBroadcaster != null) {
            s.eventBroadcaster.resourceReady(s.taskId, type, title, confidence, sourceCount);
        }
        // Persist to MySQL
        if (persistenceService != null) {
            persistenceService.recordResourceReady(s.taskId, type, title, confidence, sourceCount);
        }
    }

    private void fail(ResourceGenerationState s, String errorCode, String message, boolean retryable) {
        s.status = "FAILED";
        s.errorCode = errorCode;
        s.errorMessage = message;
        s.retryable = retryable;
        s.finishedAt = java.time.Instant.now();
    }

    private AgentContext buildContext(ResourceGenerationState s) {
        return AgentContext.builder(s.taskId, s.userId)
            .courseId(s.courseId)
            .build();
    }

    // === Interface for Publisher (not an LLM agent, but a system component) ===

    @FunctionalInterface
    public interface Publisher {
        AgentResult<String> publish(
            String taskId,
            String resourceType,
            ResourceGenerationState.GeneratedContent content,
            ResourceGenerationState.ReviewResult review,
            AgentContext ctx
        );
    }
}
