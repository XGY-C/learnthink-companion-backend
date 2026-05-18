package com.learnthink.core.agent.orchestration;

import java.time.Instant;
import java.util.*;

/**
 * The state that flows through the resource generation pipeline.
 *
 * <p>Task identity and pipeline progress fields are mutable (single-writer: advance()).
 * Stage outputs are stored as immutable records — each node reads previous stages and
 * produces its own output record, eliminating shared-mutable-state races.</p>
 */
public class ResourceGenerationState {

    // -- Task identity (final, set once via constructor) --
    public final String taskId;
    public final String userId;
    public final String courseId;
    public final String topic;
    public final List<String> resourceTypes;
    public final int profileVersion;
    public String profileVersionId; // resolved during PROFILING

    // -- Pipeline progress (mutable, single writer: advance()) --
    public String stage = "PENDING";
    public int percent;
    public String message;
    public String status = "PENDING";
    public String errorCode;
    public String errorMessage;
    public boolean retryable;

    // -- Stage outputs (immutable records) --
    public ProfileSummary profileSummary;
    public RetrievalData retrieval = RetrievalData.empty();
    public PlanningData planning = PlanningData.empty();
    public GenerationData generation = GenerationData.empty();
    public ReviewData review = ReviewData.empty();
    public PublishingData publish = PublishingData.empty();

    // -- Sub-topic iteration (for incremental publishing) --
    public SubTopicProgress subTopicProgress = SubTopicProgress.empty();

    // -- Feedback loop context --
    public FeedbackContext feedback = FeedbackContext.empty();

    // -- Timing --
    public final Map<String, Long> stageElapsedMs = new LinkedHashMap<>();
    public final Instant createdAt;
    public Instant finishedAt;

    // -- Routing (set by node implementations for the GraphRunner) --
    String _nextRoute;

    // -- Progress broadcast hooks (set by TaskGraphObserver before execution) --
    transient ProgressHook progressHook;
    transient TaskEventBroadcaster eventBroadcaster;

    public ResourceGenerationState(String taskId, String userId, String courseId,
                                   String topic, List<String> resourceTypes,
                                   int profileVersion) {
        this.taskId = taskId;
        this.userId = userId;
        this.courseId = courseId;
        this.topic = topic;
        this.resourceTypes = resourceTypes != null ? List.copyOf(resourceTypes)
            : List.of("doc", "quiz", "reading", "code", "mindmap", "video");
        this.profileVersion = profileVersion;
        this.createdAt = Instant.now();
    }

    // ================================================================
    // Stage output records (immutable)
    // ================================================================

    public record RetrievalData(
        List<SourceItem> mergedSources,
        Map<String, List<SourceItem>> evidenceByType,
        boolean forceLowConfidence,
        int totalSources
    ) {
        public static RetrievalData empty() {
            return new RetrievalData(List.of(), Map.of(), false, 0);
        }
    }

    public record PlanningData(
        ResourcePlan resourcePlan,
        int planRetryCount
    ) {
        public static PlanningData empty() {
            return new PlanningData(null, 0);
        }
        public PlanningData withResourcePlan(ResourcePlan plan) {
            return new PlanningData(plan, planRetryCount);
        }
        public PlanningData incrementRetry() {
            return new PlanningData(resourcePlan, planRetryCount + 1);
        }
    }

    public record GenerationData(
        Map<String, GeneratedContent> artifacts,
        Set<String> failedTypes,
        int regenerateCount
    ) {
        public static GenerationData empty() {
            return new GenerationData(Map.of(), Set.of(), 0);
        }
        public GenerationData withArtifact(String type, GeneratedContent content) {
            var map = new HashMap<>(artifacts);
            map.put(type, content);
            return new GenerationData(Collections.unmodifiableMap(map), failedTypes, regenerateCount);
        }
        public GenerationData withFailedType(String type) {
            var set = new HashSet<>(failedTypes);
            set.add(type);
            return new GenerationData(artifacts, Collections.unmodifiableSet(set), regenerateCount);
        }
    }

    public record ReviewData(
        Map<String, ReviewResult> reviewResults,
        int reviewRetryCount
    ) {
        public static ReviewData empty() {
            return new ReviewData(Map.of(), 0);
        }
        public ReviewData withResult(String type, ReviewResult result) {
            var map = new HashMap<>(reviewResults);
            map.put(type, result);
            return new ReviewData(Collections.unmodifiableMap(map), reviewRetryCount);
        }
        public ReviewData incrementRetry() {
            return new ReviewData(reviewResults, reviewRetryCount + 1);
        }
    }

    /** Tracks per-sub-topic processing for incremental publishing. */
    public record SubTopicProgress(
        int currentIndex,           // which sub-topic is being processed (-1 = all-at-once legacy mode)
        Set<Integer> completedIndices, // sub-topics already published
        int totalCount              // total sub-topics from plan
    ) {
        public static SubTopicProgress empty() {
            return new SubTopicProgress(-1, Set.of(), 0);
        }
        public SubTopicProgress start(int totalCount) {
            return new SubTopicProgress(0, Set.of(), totalCount);
        }
        public SubTopicProgress advance() {
            var set = new HashSet<>(completedIndices);
            set.add(currentIndex);
            return new SubTopicProgress(currentIndex + 1, Collections.unmodifiableSet(set), totalCount);
        }
        public boolean hasMore() { return currentIndex >= 0 && currentIndex < totalCount; }
        public boolean allDone() { return currentIndex >= totalCount; }
    }

    public record PublishingData(
        String packId,
        Set<String> publishedTypes
    ) {
        public static PublishingData empty() {
            return new PublishingData(null, Set.of());
        }
        public PublishingData withPackId(String packId) {
            return new PublishingData(packId, publishedTypes);
        }
        public PublishingData withPublishedType(String type) {
            var set = new HashSet<>(publishedTypes);
            set.add(type);
            return new PublishingData(packId, Collections.unmodifiableSet(set));
        }
    }

    public record FeedbackContext(
        Set<String> regenerateTypes,
        Map<String, String> reviewFeedbackByType,
        String planFeedback
    ) {
        public static FeedbackContext empty() {
            return new FeedbackContext(null, null, null);
        }
        public FeedbackContext forRegenerate(Set<String> types, Map<String, String> feedbackByType) {
            return new FeedbackContext(types, feedbackByType, null);
        }
        public FeedbackContext forReplan(String feedback) {
            return new FeedbackContext(null, null, feedback);
        }
        public FeedbackContext cleared() {
            return FeedbackContext.empty();
        }
    }

    // ================================================================
    // Data records (unchanged)
    // ================================================================

    @FunctionalInterface
    public interface ProgressHook {
        void onProgress(String stage, int percent, String message, java.util.Map<String, Object> extra);
    }

    public record ProfileSummary(
        List<String> weakTop,
        List<String> style,
        int minutesPerDay,
        String goal,
        int dimensionCount,
        String currentChapter,
        List<KpAnchor> kpAnchors
    ) {}

    public record KpAnchor(
        String kpId,
        String kpName,
        String chapterTitle,
        String dimensionKey,
        String relationType,
        String scope,
        double confidence
    ) {}

    public record SourceItem(
        String docId,
        String bookTitle,
        String bookType,
        Integer chapterIndex,
        String chapterTitle,
        String sourceType,
        String chunkId,
        String quote,
        String locator,
        String headingPath,
        double relevance
    ) {}

    /** A sub-topic decomposed from the main topic, forming a learning progression. */
    public record SubTopic(
        int index,               // 0-based index within the plan
        String title,           // sub-topic name, e.g. "二叉树的性质与分类"
        String description,     // brief description of what this covers
        List<String> focusKeyPoints, // key concepts covered by this sub-topic
        int estimatedMinutes,   // estimated study time for this sub-topic group
        String difficulty       // difficulty level for this sub-topic
    ) {}

    public record ResourcePlan(
        String topicOutline,
        List<SubTopic> subTopics,    // 4-6 sub-topics forming a learning progression
        List<ResourcePlanItem> items,
        List<String> pushReason,
        List<String> queries
    ) {}

    public record ResourcePlanItem(
        String type,
        String title,
        String difficulty,
        int estimatedMinutes,
        String format,
        List<String> keyPoints,
        String personalizationNote,
        int subTopicIndex        // which sub-topic this item belongs to (-1 = topic-level)
    ) {
        /** Backward-compatible constructor — defaults subTopicIndex to 0. */
        public ResourcePlanItem(String type, String title, String difficulty,
            int estimatedMinutes, String format, List<String> keyPoints,
            String personalizationNote) {
            this(type, title, difficulty, estimatedMinutes, format, keyPoints,
                 personalizationNote, 0);
        }
    }

    public record GeneratedContent(
        String title,
        String content,
        String contentMime,
        List<SourceItem> sources,
        String confidence,
        Map<String, Object> agentTrace
    ) {}

    public enum ReviewStatus { APPROVED, REJECTED }
    public enum ReviewAction { PUBLISH, RETRY, REJECT_PERMANENT }

    public record ReviewResult(
        ReviewStatus status,
        String confidence,
        String reviewSummary,
        List<ReviewReason> reasons,
        double citationCoverage,
        ReviewAction action
    ) {}

    public record ReviewReason(String check, String result, String detail) {}

    // -- Route constants --
    public static final String ROUTE_PLANNING = "PLANNING";
    public static final String ROUTE_GENERATING = "GENERATING";
    public static final String ROUTE_REVIEWING = "REVIEWING";
    public static final String ROUTE_PUBLISHING = "PUBLISHING";
    public static final String ROUTE_REGENERATE = "REGENERATE";
    public static final String ROUTE_REPLAN = "REPLAN";
    public static final String ROUTE_SUCCEEDED = "SUCCEEDED";
    public static final String ROUTE_FAILED = "FAILED";
    public static final String ROUTE_FALLBACK = "FALLBACK";
}
