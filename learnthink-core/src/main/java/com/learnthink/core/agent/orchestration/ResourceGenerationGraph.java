package com.learnthink.core.agent.orchestration;

import com.learnthink.core.agent.runtime.AgentContext;
import com.learnthink.core.agent.runtime.AgentObservation;
import com.learnthink.core.agent.runtime.AgentResult;
import com.learnthink.core.agent.graph.StateGraph;
import com.learnthink.core.agent.graph.GraphRunner;
import com.learnthink.core.agent.impl.*;
import com.learnthink.core.agent.manager.AgentManager;
import com.learnthink.core.agent.manager.GenerationChecklist;
import com.learnthink.core.service.PushService;
import com.learnthink.core.service.TaskPersistenceService;
import com.learnthink.core.service.VideoRenderPoller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 资源生成流水线，以 {@link StateGraph} 表达
 *
 * <h3>图拓扑（含反馈循环）</h3>
 * <pre>
 *   聊天驱动路径：
 *     PROFILING -> RETRIEVING -> PLANNING(LLM) -> GENERATING -> REVIEWING -> ILLUSTRATING -> PUBLISHING
 *                                                       ↑        ↓
 *                                                       └────────┘ 重新生成被拒绝内容
 *                                         ↑                      │
 *                                         └──────────────────────┘ 失败过多时重新规划
 *
 *   计划驱动路径（planContext != null）：
 *     PROFILING -> RETRIEVING -> PLAN_DRIVEN(无 LLM) -> GENERATING -> REVIEWING -> ILLUSTRATING -> PUBLISHING
 *                                                        ↑        ↓
 *                                                        └────────┘ 重新生成被拒绝内容
 * </pre>
 * <p>ILLUSTRATING 节点为审查通过的 doc 文档自动生成配图（SVG/Mermaid/图片），
 * 是增强步骤，失败不阻断发布，始终路由到 PUBLISHING。
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
    private final AgentManager agentManager;
    private final VideoRenderPoller videoRenderPoller;
    private final IllustrationService illustrationService;
    private final PushService pushService;
    private final java.util.concurrent.ExecutorService generatorPool =
        java.util.concurrent.Executors.newFixedThreadPool(20);

    public ResourceGenerationGraph(
        ProfileAnalyzer profileAnalyzer,
        EvidenceRetriever evidenceRetriever,
        CurriculumPlanner curriculumPlanner,
        ResourceGenerator resourceGenerator,
        ContentReviewer contentReviewer,
        Publisher publisher,
        TaskPersistenceService persistenceService,
        AgentManager agentManager,
        VideoRenderPoller videoRenderPoller,
        IllustrationService illustrationService,
        PushService pushService
    ) {
        this.profileAnalyzer = profileAnalyzer;
        this.evidenceRetriever = evidenceRetriever;
        this.curriculumPlanner = curriculumPlanner;
        this.resourceGenerator = resourceGenerator;
        this.contentReviewer = contentReviewer;
        this.publisher = publisher;
        this.persistenceService = persistenceService;
        this.agentManager = agentManager;
        this.videoRenderPoller = videoRenderPoller;
        this.illustrationService = illustrationService;
        this.pushService = pushService;
    }

    public GraphRunner<ResourceGenerationState> build() {
        return StateGraph.<ResourceGenerationState>create(ResourceGenerationState.class)
            .addNode("PROFILING", this::doProfiling, "提取学习画像摘要")
            .addNode("RETRIEVING", this::doRetrieving, "从知识库检索证据")
            .addNode("PLANNING", this::doPlanning, "规划资源结构和子主题")
            .addNode("PLAN_DRIVEN", this::doPlanDriven, "跳过 LLM，使用预规划条目")
            .addNode("GENERATING", this::doGenerating, "通过子生成器生成资源")
            .addNode("REVIEWING", this::doReviewing, "审查生成内容质量")
            .addNode("ILLUSTRATING", this::doIllustrating, "为文档资源生成配图")
            .addNode("PUBLISHING", this::doPublishing, "发布已批准的资源")
            .addNode("FALLBACK", this::doFallback, "Generate with limited evidence (degraded mode)")
            .addEdge("PROFILING", "RETRIEVING")
            .addEdge("PLANNING", "GENERATING")
            .addEdge("PLAN_DRIVEN", "GENERATING")
            .addConditionalEdge("FALLBACK", this::routeAfterFallback)
            .addConditionalEdge("RETRIEVING", this::routeAfterRetrieving)
            .addConditionalEdge("GENERATING", this::routeAfterGenerating)
            .addConditionalEdge("REVIEWING", this::routeAfterReviewing)
            .addConditionalEdge("ILLUSTRATING", this::routeAfterIllustrating)
            .addConditionalEdge("PUBLISHING", this::routeAfterPublishing)
            .setEntryPoint("PROFILING")
            .setMaxCycles(20) // sub-topic iteration (up to 6 sub-topics × 3 passes each)
            .setFailureDetector(s -> "FAILED".equals(s.status))
            .compile();
    }

    // ================================================================
    // 节点实现
    // ================================================================

    private ResourceGenerationState doProfiling(ResourceGenerationState s) {
        log.info("=== PROFILING NODE START === taskId={}, userId={}, courseId={}", s.taskId, s.userId, s.courseId);
        advance(s, "PROFILING", 0, "Analyzing learning profile...");
        AgentContext ctx = buildContext(s);
        setPipelineContext(ctx, "PROFILING", "分析学习画像，提取薄弱点和知识锚点");

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
        // 预定计划路径（聊天 items 或学习路径）：跳过集中检索，各生成器自行按需检索
        if (s.planContext != null) {
            s.retrieval = ResourceGenerationState.RetrievalData.empty();
            advance(s, "RETRIEVING", 35, "Skipping central retrieval — each generator will retrieve as needed");
            return s;
        }

        log.info("=== RETRIEVING NODE START === topic={}, resourceTypes={}", s.topic, s.resourceTypes);
        advance(s, "RETRIEVING", 15, "Retrieving course evidence...");
        AgentContext ctx = buildContext(s);
        setPipelineContext(ctx, "RETRIEVING",
            "从知识库检索证据，资源类型=" + String.join(",", s.resourceTypes));

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
                if ("KB_NOT_READY".equals(result.errorMessage())) {
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
        setPipelineContext(ctx, "PLANNING",
            "规划资源结构，主题=" + s.topic);

        var result = curriculumPlanner.plan(
            s.profileSummary, retrieval.mergedSources(), s.topic, s.resourceTypes,
            feedback.planFeedback(), ctx, s.generationMeta);
        if (!result.success()) {
            log.error("Planning failed: {}", result.errorMessage());
            fail(s, "PLAN_ERROR", result.errorMessage(), false);
            return s;
        }
        s.planning = s.planning.withResourcePlan(result.output()).incrementRetry();
        // 初始化子主题迭代以支持增量发布
        int subTopicCount = result.output().subTopics().size();
        s.subTopicProgress = s.subTopicProgress.start(subTopicCount);
        log.info("Planning completed. SubTopics: {}, Items: {}", subTopicCount, result.output().items().size());
        advance(s, "PLANNING", 55,
            "Planned " + result.output().items().size() + " resources");
        return s;
    }

    /**
     * 直接从预规划条目构建资源计划——无需 LLM 调用。
     * 每个 PrePlannedItem（对应一个 activity）可能包含多种资源类型，
     * 每种类型创建一个 ResourcePlanItem，共享同一个 subTopicIndex 和 activityId。
     */
    private ResourceGenerationState doPlanDriven(ResourceGenerationState s) {
        var preItems = s.planContext.items();
        log.info("=== PLAN_DRIVEN NODE START === pre-planned items: {}", preItems.size());
        advance(s, "PLAN_DRIVEN", 35, "Building resource plan from " + preItems.size() + " pre-planned activities...");
        AgentContext planDrivenCtx = buildContext(s);
        setPipelineContext(planDrivenCtx, "PLANNING",
            "从 " + preItems.size() + " 个预规划活动构建资源计划");

        List<ResourceGenerationState.ResourcePlanItem> items = new ArrayList<>();
        List<ResourceGenerationState.SubTopic> subTopics = new ArrayList<>();
        int itemIdx = 0;

        for (int piIdx = 0; piIdx < preItems.size(); piIdx++) {
            var pi = preItems.get(piIdx);

            // 每个 PrePlannedItem 作为一个子主题
            subTopics.add(new ResourceGenerationState.SubTopic(
                piIdx, pi.title(), pi.description(),
                pi.knowledgePoints() != null ? pi.knowledgePoints() : List.of(),
                pi.estimatedMinutes() > 0 ? pi.estimatedMinutes() : 15,
                pi.difficulty() != null ? pi.difficulty() : "medium"));

            // 每个 resourceType 创建一个 ResourcePlanItem，共享同一 subTopicIndex + activityId
            List<String> types = pi.resourceTypes();
            if (types == null || types.isEmpty()) {
                log.warn("PrePlannedItem '{}' has no resourceTypes, skipping", pi.title());
                continue;
            }
            for (String type : types) {
                items.add(new ResourceGenerationState.ResourcePlanItem(
                    type,
                    pi.title() + " - " + type,
                    pi.difficulty() != null ? pi.difficulty() : "medium",
                    pi.estimatedMinutes() > 0 ? pi.estimatedMinutes() / types.size() : 15,
                    "markdown",
                    pi.knowledgePoints() != null ? pi.knowledgePoints() : List.of(),
                    pi.description() != null ? pi.description() : "",
                    piIdx,
                    pi.activityId()));
                itemIdx++;
            }
        }

        var plan = new ResourceGenerationState.ResourcePlan(
            s.topic, subTopics, items,
            List.of("AI-customized based on your learning needs"), List.of());

        s.planning = new ResourceGenerationState.PlanningData(plan, 0);
        s.subTopicProgress = s.subTopicProgress.start(subTopics.size());

        log.info("Plan-driven setup complete. SubTopics: {}, Items: {}", subTopics.size(), items.size());
        advance(s, "PLAN_DRIVEN", 55, "Plan built from " + items.size() + " resources across " + subTopics.size() + " activities");
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

        // 确定需要生成的项
        boolean isRegeneration = feedback.regenerateTypes() != null && !feedback.regenerateTypes().isEmpty();
        List<ResourceGenerationState.ResourcePlanItem> itemsToGenerate;
        if (isRegeneration) {
            var regenerateTypes = feedback.regenerateTypes();
            itemsToGenerate = planning.resourcePlan().items().stream()
                .filter(i -> regenerateTypes.contains(i.type()))
                .toList();
            log.info("Regenerating {} types: {}", regenerateTypes.size(), regenerateTypes);
        } else {
            // 限定到当前子主题以实现增量发布
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

        setPipelineContext(ctx, "GENERATING",
            "并行生成 " + itemsToGenerate.size() + " 个资源");

        // 广播子主题开始
        broadcastSubTopicStarted(s);

        // 构建并发出清单供前端追踪
        GenerationChecklist checklist = buildChecklist(s, itemsToGenerate);
        if (s.eventBroadcaster != null) {
            s.eventBroadcaster.broadcastEvent(s.taskId, "checklist.created", checklist.toFrontendFormat());
        }

        advance(s, "GENERATING", 55, "Generating " + itemsToGenerate.size() + " resources in parallel...");

        // 在分发前将所有项标记为 GENERATING（前端可见性）
        if (checklist != null) {
            itemsToGenerate.forEach(item -> checklist.markGenerating(item.title()));
            s.eventBroadcaster.broadcastEvent(s.taskId, "checklist.updated", checklist.toFrontendFormat());
        }

        // 收集模式 — 无共享可变状态
        record GenOutcome(String type, String title, boolean success, ResourceGenerationState.GeneratedContent content) {}
        final boolean regenerate = isRegeneration;
        List<CompletableFuture<GenOutcome>> futures = itemsToGenerate.stream()
            .map(item -> CompletableFuture.supplyAsync(() -> {
                log.info("{} resource type: {}, title: {}",
                    regenerate ? "Revising" : "Generating", item.type(), item.title());
                // 广播 agent.generation.started 事件
                if (s.eventBroadcaster != null) {
                    s.eventBroadcaster.broadcastEvent(s.taskId, "agent.generation.started",
                        Map.of("jobId", s.taskId + "-" + item.type(), "resourceType", item.type(), "title", item.title()));
                }
                var typeSources = retrieval.evidenceByType().getOrDefault(item.type(), List.of());
                String reviewFeedback = feedback.reviewFeedbackByType() != null
                    ? feedback.reviewFeedbackByType().get(item.type()) : null;
                try {
                    AgentResult<ResourceGenerationState.GeneratedContent> result;
                    if (regenerate) {
                        var original = gen.artifacts().get(item.type());
                        result = resourceGenerator.revise(item, typeSources, s.profileSummary,
                            retrieval.forceLowConfidence(), reviewFeedback, original, ctx);
                    } else {
                        result = resourceGenerator.generate(item, typeSources, s.profileSummary,
                            retrieval.forceLowConfidence(), reviewFeedback, ctx);
                    }
                    if (result.success()) {
                        resourceReady(s, item.type(), result.output().title(),
                            result.output().content(),
                            result.output().confidence(),
                            result.output().sources() != null ? result.output().sources().size() : 0);
                        if (checklist != null) {
                            if ("video".equals(item.type())) {
                                checklist.markRendering(item.title());
                            } else {
                                checklist.markDone(item.title());
                            }
                        }
                        if (s.eventBroadcaster != null) {
                            s.eventBroadcaster.broadcastEvent(s.taskId, "agent.generation.done",
                                Map.of("jobId", s.taskId + "-" + item.type(), "resourceType", item.type(), "title", item.title()));
                        }
                        log.info("Successfully {} resource type: {}",
                            regenerate ? "revised" : "generated", item.type());
                        return new GenOutcome(item.type(), item.title(), true, result.output());
                    } else {
                        if (checklist != null) checklist.markFailed(item.title());
                        if (s.eventBroadcaster != null) {
                            s.eventBroadcaster.broadcastEvent(s.taskId, "agent.generation.failed",
                                Map.of("jobId", s.taskId + "-" + item.type(), "resourceType", item.type(), "title", item.title()));
                        }
                        log.warn("{} failed for type={}: {}",
                            regenerate ? "Revision" : "Generation", item.type(), result.errorMessage());
                        return new GenOutcome(item.type(), item.title(), false, null);
                    }
                } catch (Exception e) {
                    if (checklist != null) checklist.markFailed(item.title());
                    if (s.eventBroadcaster != null) {
                        s.eventBroadcaster.broadcastEvent(s.taskId, "agent.generation.failed",
                            Map.of("jobId", s.taskId + "-" + item.type(), "resourceType", item.type(), "title", item.title()));
                    }
                    log.error("{} error for type={}: {}",
                        regenerate ? "Revision" : "Generation", item.type(), e.getMessage());
                    return new GenOutcome(item.type(), item.title(), false, null);
                }
            }, generatorPool))
            .toList();

        // 将结果合并到生成记录中
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

        // 广播最终清单状态
        if (s.eventBroadcaster != null && checklist != null) {
            s.eventBroadcaster.broadcastEvent(s.taskId, "checklist.updated", checklist.toFrontendFormat());
        }

        return s;
    }

    /** 从本轮生成的资源计划条目构建清单。 */
    private GenerationChecklist buildChecklist(ResourceGenerationState s,
                                                List<ResourceGenerationState.ResourcePlanItem> items) {
        List<GenerationChecklist.ChecklistItem> listItems = new ArrayList<>();
        for (var item : items) {
            listItems.add(new GenerationChecklist.ChecklistItem(
                item.type(), item.title(),
                item.type() + " resource: " + item.title(),
                item.difficulty(), item.estimatedMinutes(), item.format(),
                item.keyPoints(), item.personalizationNote(), 1));
        }
        return new GenerationChecklist(s.taskId,
            s.planning != null && s.planning.resourcePlan() != null
                ? s.planning.resourcePlan().topicOutline() : s.topic, listItems);
    }

    private ResourceGenerationState doReviewing(ResourceGenerationState s) {
        var generation = s.generation;
        var retrieval = s.retrieval;
        var progress = s.subTopicProgress;
        log.info("=== REVIEWING NODE START === artifacts to review: {}, subTopic={}/{}",
            generation.artifacts().size(), progress.currentIndex() + 1, progress.totalCount());
        advance(s, "REVIEWING", 85, "Reviewing generated content...");
        AgentContext ctx = buildContext(s);
        setPipelineContext(ctx, "REVIEWING", "审查生成内容质量");

        // 限定到当前子主题的产物
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
            // 仅审核属于当前子主题的项
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

    /**
     * 配图节点 -- 为审查通过的 doc 类型文档自动生成配图（SVG / Mermaid / 像素图片），
     * 插入到 Markdown 对应章节位置。配图是增强步骤，失败不阻断发布。
     */
    private ResourceGenerationState doIllustrating(ResourceGenerationState s) {
        var generation = s.generation;
        var planning = s.planning;
        var progress = s.subTopicProgress;
        log.info("=== ILLUSTRATING NODE START === artifacts={}, subTopic={}/{}",
            generation.artifacts().size(), progress.currentIndex() + 1, progress.totalCount());
        advance(s, "ILLUSTRATING", 90, "为文档生成配图...");
        AgentContext ctx = buildContext(s);
        setPipelineContext(ctx, "ILLUSTRATING", "为文档资源生成配图");

        // 限定到当前子主题的 doc 产物
        int stIndex = progress.currentIndex();
        var subTopicItemTypes = planning.resourcePlan().items().stream()
            .filter(i -> i.subTopicIndex() == stIndex)
            .map(ResourceGenerationState.ResourcePlanItem::type)
            .collect(Collectors.toSet());

        ResourceGenerationState.GenerationData newGen = generation;
        for (var entry : generation.artifacts().entrySet()) {
            String type = entry.getKey();
            if (!"doc".equals(type) || !subTopicItemTypes.contains(type)) {
                continue;
            }
            var content = entry.getValue();
            try {
                if (s.eventBroadcaster != null) {
                    s.eventBroadcaster.broadcastEvent(s.taskId, "agent.illustration.started",
                        Map.of("resourceType", type, "title", content.title()));
                }
                var illustrated = illustrationService.illustrate(content, ctx);
                newGen = newGen.withArtifact(type, illustrated);
                if (s.eventBroadcaster != null) {
                    s.eventBroadcaster.broadcastEvent(s.taskId, "agent.illustration.done",
                        Map.of("resourceType", type, "title", content.title()));
                }
                log.info("Illustrated doc: {}", content.title());
            } catch (Exception e) {
                log.warn("Illustration failed for doc '{}', keeping original: {}",
                    content.title(), e.getMessage());
            }
        }
        s.generation = newGen;
        advance(s, "ILLUSTRATING", 95, "配图完成");
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
        setPipelineContext(ctx, "PUBLISHING",
            "发布子主题 " + (progress.currentIndex() + 1) + "/" + progress.totalCount());

        String packId = publish.packId() != null ? publish.packId() : UUID.randomUUID().toString();
        s.publish = publish.withPackId(packId);

        // 仅在第一个子主题时持久化资源包
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
            // 仅发布属于当前子主题的项
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

                // 对视频类型启动后台轮询，等待 Manim 渲染完成
                String itemId = null;
                if ("video".equals(type)) {
                    try {
                        itemId = UUID.randomUUID().toString();
                        String manimTaskId = extractManimTaskId(content.content());
                        if (manimTaskId != null && !manimTaskId.isBlank()) {
                            videoRenderPoller.startPolling(manimTaskId, itemId, s.taskId);
                            log.info("启动视频渲染轮询: type={}, itemId={}, manimTaskId={}",
                                type, itemId, manimTaskId);
                        }
                    } catch (Exception e) {
                        log.warn("启动视频轮询失败: {}", e.getMessage());
                    }
                }

                try {
                    if (itemId == null) {
                        itemId = UUID.randomUUID().toString();
                    }
                    String reviewStatus = rev != null
                        ? (rev.action() == ResourceGenerationState.ReviewAction.PUBLISH ? "approved" : "rejected")
                        : "pending";
                    persistenceService.saveResourceItem(itemId, packId, s.taskId,
                        s.userId, s.courseId,
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

        // 推进子主题进度并广播完成
        s.subTopicProgress = progress.advance();
        broadcastSubTopicCompleted(s);

        // 仅在所有子主题完成且至少发布了一个资源时才标记为 SUCCEEDED
        if (s.subTopicProgress.allDone()) {
            if (newPublish.publishedTypes().isEmpty() && newGen.artifacts().isEmpty()) {
                s.status = "FAILED";
                s.errorCode = "NOTHING_PUBLISHED";
                s.errorMessage = "All generated resources failed review — nothing was published";
                log.error("Task {} allDone but zero items published — marking FAILED", s.taskId);
            } else {
                s.status = "SUCCEEDED";

                // 资源生成完成 → 触发精准推送通知
                if (pushService != null && packId != null && s.userId != null && s.courseId != null) {
                    try {
                        pushService.notifyResourceReady(s.userId, s.courseId, packId,
                                "push_resource_ready", null);
                        log.info("Push notification triggered: packId={}, userId={}", packId, s.userId);
                    } catch (Exception e) {
                        log.warn("Failed to trigger push notification for packId={}: {}", packId, e.getMessage());
                    }
                }
            }
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
        AgentContext fallbackCtx = buildContext(s);
        setPipelineContext(fallbackCtx, "RETRIEVING",
            "知识库不可用，使用通用知识生成（降级模式）");

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
                        List.of(), " Generated without course evidence (KB unavailable)", 0, null))
                    .toList(),
                List.of("KB not available — use general knowledge"),
                List.of()),
            0);

        log.info("Fallback plan created for {} resource types", s.resourceTypes.size());
        s.subTopicProgress = s.subTopicProgress.start(fallbackSubTopics.size());
        s._nextRoute = "GENERATING";
        return s;
    }

    // ================================================================
    // 条件路由器
    // ================================================================

    private String routeAfterRetrieving(ResourceGenerationState s) {
        // 计划驱动：直接使用预计划项，跳过 LLM 规划
        if (s.planContext != null) {
            log.info("Plan-driven mode — routing to PLAN_DRIVEN");
            return "PLAN_DRIVEN";
        }
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

        // 永久拒绝 — 从产物中移除
        ResourceGenerationState.GenerationData newGen = gen;
        for (String type : retryPermanentTypes) {
            newGen = newGen.withFailedType(type);
            // 通过创建不含该类型的新映射从产物中移除
            var filtered = new HashMap<>(newGen.artifacts());
            filtered.remove(type);
            newGen = new ResourceGenerationState.GenerationData(
                Collections.unmodifiableMap(filtered),
                newGen.failedTypes(),
                newGen.regenerateCount());
        }
        s.generation = newGen;

        if (rejectedTypes.isEmpty()) {
            log.info("All resources approved - routing to ILLUSTRATING");
            return "ILLUSTRATING";
        }

        String feedback = feedbackBuilder.toString();
        log.info("Review feedback: {} rejected types — {}", rejectedTypes.size(), feedback);

        // 决策：重新规划还是重新生成？
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

        // 超过最大重试次数 — 带失败继续执行
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
        return "ILLUSTRATING";
    }

    private String routeAfterIllustrating(ResourceGenerationState s) {
        log.info("Routing from ILLUSTRATING to PUBLISHING");
        return "PUBLISHING";
    }

    private String routeAfterPublishing(ResourceGenerationState s) {
        var progress = s.subTopicProgress;
        if (progress.hasMore()) {
            // 为下一个子主题清除反馈
            s.feedback = ResourceGenerationState.FeedbackContext.empty();
            log.info("Advancing to sub-topic {}/{}", progress.currentIndex() + 1, progress.totalCount());
            return "GENERATING";
        }
        log.info("All {} sub-topics published", progress.totalCount());
        return null; // 终止 — 图结束
    }

    // ================================================================
    // 辅助方法
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

    private static final int SSE_CONTENT_MAX_LENGTH = 20000;

    private void resourceReady(ResourceGenerationState s, String type, String title, String content, String confidence, int sourceCount) {
        String truncatedContent = content != null && content.length() > SSE_CONTENT_MAX_LENGTH
            ? content.substring(0, SSE_CONTENT_MAX_LENGTH) : content;
        if (s.eventBroadcaster != null) {
            s.eventBroadcaster.resourceReady(s.taskId, type, title, truncatedContent, confidence, sourceCount);
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

    private void setPipelineContext(AgentContext ctx, String stage, String taskDesc) {
        if (ctx.observation() instanceof SseAgentObservation sseObs) {
            sseObs.setPipelineStage(stage);
            sseObs.setCurrentTaskDesc(taskDesc);
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
        var observation = s.eventBroadcaster != null
            ? new SseAgentObservation(s.taskId, s.eventBroadcaster)
            : AgentObservation.NOOP;
        return AgentContext.builder(s.taskId, s.userId)
            .courseId(s.courseId)
            .observation(observation)
            .build();
    }

    private String extractManimTaskId(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) return null;
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(contentJson);
            var idNode = node.get("manimTaskId");
            return idNode != null ? idNode.asText() : null;
        } catch (Exception e) {
            log.debug("解析manimTaskId失败: {}", e.getMessage());
            return null;
        }
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
