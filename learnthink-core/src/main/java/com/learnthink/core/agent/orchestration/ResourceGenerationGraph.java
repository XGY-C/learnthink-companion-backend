package com.learnthink.core.agent.orchestration;

import com.learnthink.core.agent.framework.AgentContext;
import com.learnthink.core.agent.framework.AgentResult;
import com.learnthink.core.agent.graph.StateGraph;
import com.learnthink.core.agent.graph.GraphRunner;
import com.learnthink.core.agent.impl.*;
import com.learnthink.core.service.TaskPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
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
 */
public class ResourceGenerationGraph {

    private static final Logger log = LoggerFactory.getLogger(ResourceGenerationGraph.class);

    static final int MAX_REGENERATE = 2;
    static final int MAX_REPLAN = 1;
    static final int REPLAN_THRESHOLD = 3;

    private final ProfileAnalyzer profileAnalyzer;
    private final EvidenceRetriever evidenceRetriever;
    private final CurriculumPlanner curriculumPlanner;
    private final ResourceGenerator resourceGenerator;
    private final ContentReviewer contentReviewer;
    private final Publisher publisher;
    private final TaskPersistenceService persistenceService;
    private final java.util.concurrent.ExecutorService generatorPool =
        java.util.concurrent.Executors.newFixedThreadPool(5);

    public ResourceGenerationGraph(
        ProfileAnalyzer profileAnalyzer,
        EvidenceRetriever evidenceRetriever,
        CurriculumPlanner curriculumPlanner,
        ResourceGenerator resourceGenerator,
        ContentReviewer contentReviewer,
        Publisher publisher,
        TaskPersistenceService persistenceService
    ) {
        this.profileAnalyzer = profileAnalyzer;
        this.evidenceRetriever = evidenceRetriever;
        this.curriculumPlanner = curriculumPlanner;
        this.resourceGenerator = resourceGenerator;
        this.contentReviewer = contentReviewer;
        this.publisher = publisher;
        this.persistenceService = persistenceService;
    }

    public GraphRunner<ResourceGenerationState> build() {
        return StateGraph.<ResourceGenerationState>create(ResourceGenerationState.class)
            .addNode("PROFILING", this::doProfiling, "Extract learning profile summary")
            .addNode("RETRIEVING", this::doRetrieving, "Retrieve evidence from knowledge base")
            .addNode("PLANNING", this::doPlanning, "Plan resource composition and outline")
            .addNode("GENERATING", this::doGenerating, "Generate all resource types")
            .addNode("REVIEWING", this::doReviewing, "Review generated content for quality and safety")
            .addNode("PUBLISHING", this::doPublishing, "Persist approved resources")
            .addNode("FALLBACK", this::doFallback, "Generate with limited evidence (degraded mode)")
            .addEdge("PROFILING", "RETRIEVING")
            .addEdge("PLANNING", "GENERATING")
            .addConditionalEdge("FALLBACK", this::routeAfterFallback)
            .addConditionalEdge("RETRIEVING", this::routeAfterRetrieving)
            .addConditionalEdge("GENERATING", this::routeAfterGenerating)
            .addConditionalEdge("REVIEWING", this::routeAfterReviewing)
            .addConditionalEdge("PUBLISHING", this::routeAfterPublishing)
            .setEntryPoint("PROFILING")
            .setMaxCycles(20) // sub-topic iteration (up to 6 sub-topics × 3 passes each)
            .compile();
    }

    // ================================================================
    // Node implementations
    // ================================================================

    private ResourceGenerationState doProfiling(ResourceGenerationState s) {
        log.info("=== PROFILING NODE START === taskId={}, userId={}, courseId={}", s.taskId, s.userId, s.courseId);
        advance(s, "PROFILING", 0, "Analyzing learning profile...");
        AgentContext ctx = buildContext(s);

        s.profileVersionId = persistenceService.resolveProfileVersionId(s.userId, s.courseId, s.profileVersion);

        var result = profileAnalyzer.summarize(s.userId, s.courseId, s.profileVersion,
            s.profileVersionId, ctx);
        if (!result.success()) {
            log.error("Profiling failed: {}", result.errorMessage());
            fail(s, "PROFILE_NOT_READY", result.errorMessage(), false);
            return s;
        }
        s.profileSummary = result.output();
        log.info("Profiling completed. Dimensions: {}, Anchors: {}, pvId: {}",
            s.profileSummary.dimensionCount(),
            s.profileSummary.kpAnchors() != null ? s.profileSummary.kpAnchors().size() : 0,
            s.profileVersionId);
        advance(s, "PROFILING", 15, "Profile analysis complete (" + s.profileSummary.dimensionCount() + " dimensions)");
        return s;
    }

    private ResourceGenerationState doRetrieving(ResourceGenerationState s) {
        log.info("=== RETRIEVING NODE START === topic={}, resourceTypes={}", s.topic, s.resourceTypes);
        advance(s, "RETRIEVING", 15, "Retrieving course evidence...");
        AgentContext ctx = buildContext(s);

        Map<String, List<ResourceGenerationState.SourceItem>> evidenceByType = new HashMap<>();
        List<ResourceGenerationState.SourceItem> merged = new ArrayList<>();
        int completed = 0;
        int totalSources = 0;
        boolean forceLowConfidence = false;

        for (String resourceType : s.resourceTypes) {
            log.info("Retrieving evidence for type: {}", resourceType);
            var result = evidenceRetriever.retrieve(s.courseId, s.topic, resourceType, ctx);
            if (!result.success()) {
                log.warn("Retrieval failed for type {}: {}", resourceType, result.errorMessage());
                if ("KB_NOT_READY".equals(s.errorCode)) {
                    log.info("Knowledge base not ready, routing to FALLBACK");
                    return s;
                }
                forceLowConfidence = true;
                continue;
            }
            var pack = result.output();
            if (pack != null && !pack.isEmpty()) {
                evidenceByType.put(resourceType, pack);
                merged.addAll(pack);
                totalSources += pack.size();
                log.info("Retrieved {} sources for type {}", pack.size(), resourceType);
            }
            completed++;
            int pct = 15 + (completed * 20 / s.resourceTypes.size());
            advance(s, "RETRIEVING", pct,
                "Retrieving evidence... (" + completed + "/" + s.resourceTypes.size() + ")");
        }

        forceLowConfidence = forceLowConfidence || totalSources == 0;

        s.retrieval = new ResourceGenerationState.RetrievalData(
            merged.stream().distinct().collect(Collectors.toList()),
            Collections.unmodifiableMap(evidenceByType),
            forceLowConfidence,
            totalSources);

        log.info("Retrieval completed. Total sources: {}, Evidence by type: {}", totalSources, evidenceByType.keySet());
        advance(s, "RETRIEVING", 35, "Retrieved " + totalSources + " evidence chunks");
        return s;
    }

    private ResourceGenerationState doPlanning(ResourceGenerationState s) {
        var retrieval = s.retrieval;
        var feedback = s.feedback;
        log.info("=== PLANNING NODE START === topic={}, feedback={}",
            s.topic, feedback.planFeedback() != null ? "with feedback" : "initial");
        advance(s, "PLANNING", 35, "Planning resource pack...");
        AgentContext ctx = buildContext(s);

        var result = curriculumPlanner.plan(
            s.profileSummary, retrieval.mergedSources(), s.topic, s.resourceTypes,
            feedback.planFeedback(), ctx);
        if (!result.success()) {
            log.error("Planning failed: {}", result.errorMessage());
            fail(s, "PLAN_ERROR", result.errorMessage(), false);
            return s;
        }
        s.planning = s.planning.withResourcePlan(result.output()).incrementRetry();
        // Initialize sub-topic iteration for incremental publishing
        int subTopicCount = result.output().subTopics().size();
        s.subTopicProgress = s.subTopicProgress.start(subTopicCount);
        log.info("Planning completed. SubTopics: {}, Items: {}", subTopicCount, result.output().items().size());
        advance(s, "PLANNING", 55,
            "Planned " + result.output().items().size() + " resources");
        return s;
    }

    private ResourceGenerationState doGenerating(ResourceGenerationState s) {
        var gen = s.generation;
        var retrieval = s.retrieval;
        var feedback = s.feedback;
        var planning = s.planning;
        var progress = s.subTopicProgress;
        log.info("=== GENERATING NODE START === artifacts={}, failedTypes={}, subTopic={}/{}",
            gen.artifacts().size(), gen.failedTypes().size(),
            progress.currentIndex() + 1, progress.totalCount());
        AgentContext ctx = buildContext(s);
        ctx.put("forceLowConfidence", retrieval.forceLowConfidence());

        // Determine which items to generate
        boolean isRegeneration = feedback.regenerateTypes() != null && !feedback.regenerateTypes().isEmpty();
        List<ResourceGenerationState.ResourcePlanItem> itemsToGenerate;
        if (isRegeneration) {
            var regenerateTypes = feedback.regenerateTypes();
            itemsToGenerate = planning.resourcePlan().items().stream()
                .filter(i -> regenerateTypes.contains(i.type()))
                .toList();
            log.info("Regenerating {} types: {}", regenerateTypes.size(), regenerateTypes);
        } else {
            // Scope to current sub-topic for incremental publishing
            int stIndex = progress.currentIndex();
            itemsToGenerate = planning.resourcePlan().items().stream()
                .filter(i -> i.subTopicIndex() == stIndex)
                .toList();
            log.info("Generating {} items for sub-topic {}/{}", itemsToGenerate.size(),
                stIndex + 1, progress.totalCount());
        }

        if (itemsToGenerate.isEmpty()) {
            log.info("No resources to generate in this pass");
            advance(s, "GENERATING", 85, "No resources to generate");
            return s;
        }

        // Broadcast sub-topic start
        broadcastSubTopicStarted(s);

        advance(s, "GENERATING", 55, "Generating " + itemsToGenerate.size() + " resources in parallel...");

        // Collect pattern — no shared mutable state
        record GenOutcome(String type, boolean success, ResourceGenerationState.GeneratedContent content) {}
        final boolean regenerate = isRegeneration;
        List<CompletableFuture<GenOutcome>> futures = itemsToGenerate.stream()
            .map(item -> CompletableFuture.supplyAsync(() -> {
                log.info("{} resource type: {}, title: {}",
                    regenerate ? "Revising" : "Generating", item.type(), item.title());
                var typeSources = retrieval.evidenceByType().getOrDefault(item.type(), List.of());
                String reviewFeedback = feedback.reviewFeedbackByType() != null
                    ? feedback.reviewFeedbackByType().get(item.type()) : null;
                try {
                    AgentResult<ResourceGenerationState.GeneratedContent> result;
                    if (regenerate) {
                        // Targeted revision based on review feedback
                        var original = gen.artifacts().get(item.type());
                        result = resourceGenerator.revise(item, typeSources, s.profileSummary,
                            retrieval.forceLowConfidence(), reviewFeedback, original, ctx);
                    } else {
                        result = resourceGenerator.generate(item, typeSources, s.profileSummary,
                            retrieval.forceLowConfidence(), reviewFeedback, ctx);
                    }
                    if (result.success()) {
                        resourceReady(s, item.type(), result.output().title(),
                            result.output().confidence(),
                            result.output().sources() != null ? result.output().sources().size() : 0);
                        log.info("Successfully {} resource type: {}",
                            regenerate ? "revised" : "generated", item.type());
                        return new GenOutcome(item.type(), true, result.output());
                    } else {
                        log.warn("{} failed for type={}: {}",
                            regenerate ? "Revision" : "Generation", item.type(), result.errorMessage());
                        return new GenOutcome(item.type(), false, null);
                    }
                } catch (Exception e) {
                    log.error("{} error for type={}: {}",
                        regenerate ? "Revision" : "Generation", item.type(), e.getMessage());
                    return new GenOutcome(item.type(), false, null);
                }
            }, generatorPool))
            .toList();

        // Merge results into the generation record
        ResourceGenerationState.GenerationData newGen = gen;
        for (var future : futures) {
            GenOutcome outcome = future.join();
            if (outcome.success()) {
                newGen = newGen.withArtifact(outcome.type(), outcome.content());
            } else {
                newGen = newGen.withFailedType(outcome.type());
            }
        }
        s.generation = newGen;

        log.info("Generation completed. Successful: {}, Failed: {}",
            newGen.artifacts().size(), newGen.failedTypes().size());
        advance(s, "GENERATING", 85,
            "Resources generated (" + newGen.artifacts().size() + "/" + itemsToGenerate.size() + " successful)");
        return s;
    }

    private ResourceGenerationState doReviewing(ResourceGenerationState s) {
        var generation = s.generation;
        var retrieval = s.retrieval;
        var progress = s.subTopicProgress;
        log.info("=== REVIEWING NODE START === artifacts to review: {}, subTopic={}/{}",
            generation.artifacts().size(), progress.currentIndex() + 1, progress.totalCount());
        advance(s, "REVIEWING", 85, "Reviewing generated content...");
        AgentContext ctx = buildContext(s);

        // Scope to current sub-topic's artifacts
        var planning = s.planning;
        int stIndex = progress.currentIndex();
        var subTopicItemTypes = planning.resourcePlan().items().stream()
            .filter(i -> i.subTopicIndex() == stIndex)
            .map(ResourceGenerationState.ResourcePlanItem::type)
            .collect(Collectors.toSet());

        ResourceGenerationState.ReviewData review = s.review;
        int reviewCompleted = 0;
        int totalToReview = (int) generation.artifacts().keySet().stream()
            .filter(subTopicItemTypes::contains).count();
        if (totalToReview == 0) totalToReview = 1;

        for (var entry : generation.artifacts().entrySet()) {
            String type = entry.getKey();
            // Only review items belonging to the current sub-topic
            if (!subTopicItemTypes.contains(type)) {
                continue;
            }
            var content = entry.getValue();
            var typeSources = retrieval.evidenceByType().getOrDefault(type, List.of());

            log.info("Reviewing resource type: {} (subTopic {})", type, stIndex);
            advance(s, "REVIEWING", 85 + (reviewCompleted * 10 / totalToReview),
                "Reviewing: " + type);

            var result = contentReviewer.review(content, typeSources, type, retrieval.forceLowConfidence(), ctx);
            review = review.withResult(type, result.output());

            log.info("Review result for {}: action={}, confidence={}",
                    type, result.output().action(), result.output().confidence());
            ctx.observation().onDecision("ContentReviewer",
                result.output().action().name(),
                result.output().reviewSummary());

            if (result.output().action() != ResourceGenerationState.ReviewAction.PUBLISH) {
                log.info("Review flagged issue for {}: action={}, summary={}",
                        type, result.output().action(), result.output().reviewSummary());
                if (s.eventBroadcaster != null) {
                    s.eventBroadcaster.reviewFlag(s.taskId, type,
                        result.output().action().name(),
                        result.output().confidence(),
                        result.output().citationCoverage());
                }
            }

            if (persistenceService != null) {
                try {
                    persistenceService.recordReviewFlag(s.taskId, type,
                        result.output().action().name(),
                        result.output().confidence(),
                        result.output().citationCoverage());
                } catch (Exception e) {
                    log.warn("Failed to persist review flag event: {}", e.getMessage());
                }
            }

            reviewCompleted++;
        }

        s.review = review.incrementRetry();
        log.info("Reviewing completed. Reviewed {} resources", totalToReview);
        advance(s, "REVIEWING", 95, "Review complete (" + totalToReview + " resources)");
        return s;
    }

    private ResourceGenerationState doPublishing(ResourceGenerationState s) {
        var publish = s.publish;
        var generation = s.generation;
        var review = s.review;
        var planning = s.planning;
        var progress = s.subTopicProgress;
        log.info("=== PUBLISHING NODE START === publishedTypes={}, failedTypes={}, subTopic={}/{}",
            publish.publishedTypes().size(), generation.failedTypes().size(),
            progress.currentIndex() + 1, progress.totalCount());
        advance(s, "PUBLISHING", 95,
            "Publishing sub-topic " + (progress.currentIndex() + 1) + "/" + progress.totalCount() + "...");
        AgentContext ctx = buildContext(s);

        String packId = publish.packId() != null ? publish.packId() : UUID.randomUUID().toString();
        s.publish = publish.withPackId(packId);

        // Persist resource_pack on first sub-topic only
        if (progress.completedIndices().isEmpty()) {
            try {
                List<String> pushReasons = planning.resourcePlan() != null
                    ? planning.resourcePlan().pushReason() : List.of();
                persistenceService.saveResourcePack(packId, s.userId, s.courseId, s.topic,
                    s.taskId, s.profileVersionId, pushReasons);
            } catch (Exception e) {
                log.warn("Failed to save resource pack: {}", e.getMessage());
            }
        }

        int stIndex = progress.currentIndex();
        var subTopicItemTypes = planning.resourcePlan().items().stream()
            .filter(i -> i.subTopicIndex() == stIndex)
            .map(ResourceGenerationState.ResourcePlanItem::type)
            .collect(Collectors.toSet());

        ResourceGenerationState.PublishingData newPublish = s.publish;
        ResourceGenerationState.GenerationData newGen = generation;

        for (var entry : generation.artifacts().entrySet()) {
            String type = entry.getKey();
            // Only publish items belonging to the current sub-topic
            if (!subTopicItemTypes.contains(type)) {
                continue;
            }
            var content = entry.getValue();
            var rev = review.reviewResults().get(type);

            if (rev != null && rev.action() == ResourceGenerationState.ReviewAction.REJECT_PERMANENT) {
                log.info("Skipping permanently rejected resource type: {}", type);
                newGen = newGen.withFailedType(type);
                continue;
            }

            log.info("Publishing resource type: {}", type);
            var result = publisher.publish(s.taskId, type, content, rev, ctx);
            if (result.success()) {
                newPublish = newPublish.withPublishedType(type);

                try {
                    String itemId = UUID.randomUUID().toString();
                    String reviewStatus = rev != null
                        ? (rev.action() == ResourceGenerationState.ReviewAction.PUBLISH ? "approved" : "rejected")
                        : "pending";
                    persistenceService.saveResourceItem(itemId, packId, s.taskId,
                        type, content.title(), content.content(),
                        content.contentMime(), content.confidence(),
                        content.sources() != null
                            ? content.sources().stream().map(src -> {
                                java.util.Map<String, Object> sourceMap = new java.util.HashMap<>();
                                sourceMap.put("doc_id", src.docId());
                                sourceMap.put("book_title", src.bookTitle());
                                sourceMap.put("book_type", src.bookType());
                                sourceMap.put("chapter_index", src.chapterIndex());
                                sourceMap.put("chapter_title", src.chapterTitle());
                                sourceMap.put("source_type", src.sourceType());
                                sourceMap.put("chunk_id", src.chunkId());
                                sourceMap.put("quote", src.quote());
                                sourceMap.put("locator", src.locator());
                                sourceMap.put("heading_path", src.headingPath());
                                return sourceMap;
                            }).collect(java.util.stream.Collectors.toList())
                            : List.of(),
                        reviewStatus,
                        rev != null ? rev.reviewSummary() : "",
                        stIndex);
                    if (rev != null) {
                        persistenceService.recordReview(
                            itemId, packId, s.taskId,
                            rev.action() == ResourceGenerationState.ReviewAction.PUBLISH ? "approved" : "rejected",
                            rev.reviewSummary(),
                            rev.citationCoverage());
                    }
                } catch (Exception e) {
                    log.warn("Failed to persist resource item for type {}: {}", type, e.getMessage());
                }

                log.info("Successfully published resource type: {}", type);
            } else {
                log.warn("Failed to publish resource type: {}", type);
                newGen = newGen.withFailedType(type);
            }
        }

        if (s.publish.packId() == null) {
            newPublish = newPublish.withPackId(UUID.randomUUID().toString());
        }

        s.publish = newPublish;
        s.generation = newGen;

        // Advance sub-topic progress and broadcast completion
        s.subTopicProgress = progress.advance();
        broadcastSubTopicCompleted(s);

        // Only mark SUCCEEDED when ALL sub-topics are done
        if (s.subTopicProgress.allDone()) {
            s.status = "SUCCEEDED";
            s.finishedAt = Instant.now();
        }

        log.info("Sub-topic {}/{} published. Total published: {}, Failed: {}",
            progress.currentIndex() + 1, progress.totalCount(),
            newPublish.publishedTypes().size(), newGen.failedTypes().size());
        int pct = 85 + (15 * s.subTopicProgress.completedIndices().size() / Math.max(1, progress.totalCount()));
        advance(s, "PUBLISHING", pct,
            "Sub-topic " + (progress.currentIndex() + 1) + "/" + progress.totalCount() + " published");
        return s;
    }

    private ResourceGenerationState doFallback(ResourceGenerationState s) {
        log.info("=== FALLBACK NODE START === KB unavailable, using limited guidance");
        advance(s, "FALLBACK", 35, "Knowledge base unavailable — generating with limited guidance...");

        s.retrieval = new ResourceGenerationState.RetrievalData(List.of(), Map.of(), true, 0);
        var fallbackSubTopics = List.of(new ResourceGenerationState.SubTopic(
            0, s.topic, "Full topic (fallback mode — KB unavailable)",
            List.of(), 30, "medium"));
        s.planning = new ResourceGenerationState.PlanningData(
            new ResourceGenerationState.ResourcePlan(
                "# " + s.topic,
                fallbackSubTopics,
                s.resourceTypes.stream()
                    .map(t -> new ResourceGenerationState.ResourcePlanItem(
                        t, s.topic + " - " + t, "medium", 15, "markdown",
                        List.of(), " Generated without course evidence (KB unavailable)", 0))
                    .toList(),
                List.of("KB not available — use general knowledge"),
                List.of()),
            0);

        log.info("Fallback plan created for {} resource types", s.resourceTypes.size());
        s._nextRoute = "GENERATING";
        return s;
    }

    // ================================================================
    // Conditional routers
    // ================================================================

    private String routeAfterRetrieving(ResourceGenerationState s) {
        var retrieval = s.retrieval;
        if (retrieval.forceLowConfidence() && retrieval.totalSources() == 0) {
            log.info("KB not ready — routing to FALLBACK");
            return "FALLBACK";
        }
        log.info("Routing from RETRIEVING to PLANNING");
        return "PLANNING";
    }

    private String routeAfterFallback(ResourceGenerationState s) {
        log.info("Routing from FALLBACK to GENERATING");
        return "GENERATING";
    }

    private String routeAfterGenerating(ResourceGenerationState s) {
        var gen = s.generation;
        if (gen.artifacts().isEmpty() && !gen.failedTypes().isEmpty()) {
            log.error("All generation failed - terminating task");
            s.status = "FAILED";
            s.errorCode = "GENERATE_ALL_FAILED";
            return null;
        }
        log.info("Routing from GENERATING to REVIEWING");
        return "REVIEWING";
    }

    private String routeAfterReviewing(ResourceGenerationState s) {
        var review = s.review;
        var gen = s.generation;
        var planning = s.planning;

        Set<String> rejectedTypes = new HashSet<>();
        Set<String> retryPermanentTypes = new HashSet<>();
        StringBuilder feedbackBuilder = new StringBuilder();

        for (var entry : review.reviewResults().entrySet()) {
            String type = entry.getKey();
            var result = entry.getValue();
            if (result.action() == ResourceGenerationState.ReviewAction.RETRY) {
                rejectedTypes.add(type);
                feedbackBuilder.append("[").append(type).append("]: ").append(result.reviewSummary()).append("; ");
            } else if (result.action() == ResourceGenerationState.ReviewAction.REJECT_PERMANENT) {
                retryPermanentTypes.add(type);
            }
        }

        // Permanent rejections — remove from artifacts
        ResourceGenerationState.GenerationData newGen = gen;
        for (String type : retryPermanentTypes) {
            newGen = newGen.withFailedType(type);
            // Remove from artifacts by creating a new map without this type
            var filtered = new HashMap<>(newGen.artifacts());
            filtered.remove(type);
            newGen = new ResourceGenerationState.GenerationData(
                Collections.unmodifiableMap(filtered),
                newGen.failedTypes(),
                newGen.regenerateCount());
        }
        s.generation = newGen;

        if (rejectedTypes.isEmpty()) {
            log.info("All resources approved - routing to PUBLISHING");
            return "PUBLISHING";
        }

        String feedback = feedbackBuilder.toString();
        log.info("Review feedback: {} rejected types — {}", rejectedTypes.size(), feedback);

        // Decision: replan or regenerate?
        if (rejectedTypes.size() >= REPLAN_THRESHOLD && planning.planRetryCount() <= MAX_REPLAN) {
            log.info("Replanning due to {} rejected types (threshold: {})", rejectedTypes.size(), REPLAN_THRESHOLD);
            s.feedback = s.feedback.forReplan(
                "Previous plan had issues: " + feedback +
                ". Please restructure the outline and adjust difficulty/scope for the rejected types.");
            return "PLANNING";
        }

        if (review.reviewRetryCount() <= MAX_REGENERATE) {
            log.info("Regenerating {} rejected types (attempt {}/{})",
                rejectedTypes.size(), review.reviewRetryCount(), MAX_REGENERATE);
            Map<String, String> feedbackByType = new HashMap<>();
            for (String type : rejectedTypes) {
                var rev = review.reviewResults().get(type);
                if (rev != null) {
                    feedbackByType.put(type, rev.reviewSummary());
                }
            }
            s.feedback = s.feedback.forRegenerate(rejectedTypes, feedbackByType);
            return "GENERATING";
        }

        // Max retries exceeded — proceed with failures
        log.warn("Max regenerate/replan exceeded — proceeding with {} failed types", rejectedTypes.size());
        for (String type : rejectedTypes) {
            newGen = newGen.withFailedType(type);
            var filtered = new HashMap<>(newGen.artifacts());
            filtered.remove(type);
            newGen = new ResourceGenerationState.GenerationData(
                Collections.unmodifiableMap(filtered),
                newGen.failedTypes(),
                newGen.regenerateCount());
        }
        s.generation = newGen;
        return "PUBLISHING";
    }

    private String routeAfterPublishing(ResourceGenerationState s) {
        var progress = s.subTopicProgress;
        if (progress.hasMore()) {
            // Clear feedback for the next sub-topic
            s.feedback = ResourceGenerationState.FeedbackContext.empty();
            log.info("Advancing to sub-topic {}/{}", progress.currentIndex() + 1, progress.totalCount());
            return "GENERATING";
        }
        log.info("All {} sub-topics published", progress.totalCount());
        return null; // terminal — graph ends
    }

    // ================================================================
    // Helpers
    // ================================================================

    private void advance(ResourceGenerationState s, String stage, int percent, String message) {
        s.stage = stage;
        s.percent = percent;
        s.message = message;
        s.status = "RUNNING";

        if (persistenceService != null) {
            try {
                persistenceService.updateTaskStage(s.taskId, stage, percent, "RUNNING");
                persistenceService.recordStageEvent(s.taskId, stage, percent, message, null);
            } catch (Exception e) {
                log.warn("Failed to persist stage event: {}", e.getMessage());
            }
        }

        if (s.progressHook != null) {
            java.util.Map<String, Object> extra = new java.util.HashMap<>();
            extra.put("timestamp", System.currentTimeMillis());
            var plan = s.planning.resourcePlan();
            if (plan != null && plan.items() != null && !plan.items().isEmpty()) {
                extra.put("resourceTypes", plan.items().stream()
                    .map(ResourceGenerationState.ResourcePlanItem::type).toList());
                extra.put("subTopicCount", plan.subTopics().size());
            }
            try {
                s.progressHook.onProgress(stage, percent, message, extra);
            } catch (Exception e) {
                log.warn("SSE broadcast failed (client disconnected): {}", e.getMessage());
            }
        }
    }

    private void resourceReady(ResourceGenerationState s, String type, String title, String confidence, int sourceCount) {
        if (s.eventBroadcaster != null) {
            s.eventBroadcaster.resourceReady(s.taskId, type, title, confidence, sourceCount);
        }
        if (persistenceService != null) {
            persistenceService.recordResourceReady(s.taskId, type, title, confidence, sourceCount);
        }
    }

    private void broadcastSubTopicStarted(ResourceGenerationState s) {
        var progress = s.subTopicProgress;
        var plan = s.planning.resourcePlan();
        if (s.eventBroadcaster != null && plan != null && progress.currentIndex() < plan.subTopics().size()) {
            var st = plan.subTopics().get(progress.currentIndex());
            s.eventBroadcaster.broadcastEvent(s.taskId, "subtopic.started", Map.of(
                "index", progress.currentIndex(),
                "total", progress.totalCount(),
                "title", st.title(),
                "description", st.description(),
                "itemCount", plan.items().stream()
                    .filter(i -> i.subTopicIndex() == progress.currentIndex()).count()
            ));
        }
    }

    private void broadcastSubTopicCompleted(ResourceGenerationState s) {
        var progress = s.subTopicProgress;
        var plan = s.planning.resourcePlan();
        if (s.eventBroadcaster != null && plan != null) {
            int idx = progress.currentIndex();
            if (idx < plan.subTopics().size()) {
                var st = plan.subTopics().get(idx);
                s.eventBroadcaster.broadcastEvent(s.taskId, "subtopic.completed", Map.of(
                    "index", idx,
                    "total", progress.totalCount(),
                    "title", st.title(),
                    "publishedItems", s.generation.artifacts().keySet().stream()
                        .filter(t -> plan.items().stream()
                            .anyMatch(i -> i.subTopicIndex() == idx && i.type().equals(t)))
                        .toList()
                ));
            }
        }
    }

    private void fail(ResourceGenerationState s, String errorCode, String message, boolean retryable) {
        s.status = "FAILED";
        s.errorCode = errorCode;
        s.errorMessage = message;
        s.retryable = retryable;
        s.finishedAt = Instant.now();
    }

    private AgentContext buildContext(ResourceGenerationState s) {
        return AgentContext.builder(s.taskId, s.userId)
            .courseId(s.courseId)
            .build();
    }

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
