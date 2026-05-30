package com.learnthink.core.agent.orchestration;

import java.time.Instant;
import java.util.*;

/**
 * 资源生成流水线的流转状态
 * <p>任务标识和流水线进度字段是可变的（单写入器：advance()）。
 * 阶段输出存储为不可变记录——每个节点读取前一阶段并生成自己的输出记录，
 * 消除了共享可变状态的数据竞争。</p>
 */
public class ResourceGenerationState {

    // -- 任务标识（final，通过构造函数一次性设置） --
    public final String taskId;
    public final String userId;
    public final String courseId;
    public final String topic;
    public final List<String> resourceTypes;
    public final int profileVersion;
    public String profileVersionId; // 在 PROFILING 阶段解析

    // -- 管线进度（可变，单写者：advance()） --
    public String stage = "PENDING";
    public int percent;
    public String message;
    public String status = "PENDING";
    public String errorCode;
    public String errorMessage;
    public boolean retryable;

    // -- 阶段输出（不可变记录） --
    public ProfileSummary profileSummary;
    public RetrievalData retrieval = RetrievalData.empty();
    public PlanningData planning = PlanningData.empty();
    public GenerationData generation = GenerationData.empty();
    public ReviewData review = ReviewData.empty();
    public PublishingData publish = PublishingData.empty();

    // -- 子主题迭代（用于增量发布） --
    public SubTopicProgress subTopicProgress = SubTopicProgress.empty();

    // -- 计划驱动上下文（非 null → 跳过 LLM 规划，使用预计划项） --
    public PlanDrivenGenerationRequest planContext;

    // -- 来自聊天规划器的用户意图约束（携带 estimatedCount、difficulty 等） --
    public Map<String, Object> generationMeta;

    // -- 反馈循环上下文 --
    public FeedbackContext feedback = FeedbackContext.empty();

    // -- 计时 --
    public final Map<String, Long> stageElapsedMs = new LinkedHashMap<>();
    public final Instant createdAt;
    public Instant finishedAt;

    // -- 路由（由节点实现为 GraphRunner 设置） --
    String _nextRoute;

    // -- 进度广播钩子（由 TaskGraphObserver 在执行前设置） --
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
    // 阶段输出记录（不可变）
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

    /** 追踪逐子主题的处理进度，支持增量发布 */
    public record SubTopicProgress(
        int currentIndex,           // 当前正在处理的子主题索引（-1 = 一次性处理的遗留模式）
        Set<Integer> completedIndices, // 已发布的子主题
        int totalCount              // 计划中的子主题总数
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
    // 数据记录（不变）
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

    /** 从主主题分解出的子主题，形成学习进度序列 */
    public record SubTopic(
        int index,               // 计划中基于 0 的索引
        String title,           // 子主题名称，如"二叉树的性质与分类"
        String description,     // 该子主题覆盖内容的简要描述
        List<String> focusKeyPoints, // 该子主题涵盖的关键概念
        int estimatedMinutes,   // 该子主题组的预计学习时间（分钟）
        String difficulty       // 该子主题的难度等级
    ) {}

    public record ResourcePlan(
        String topicOutline,
        List<SubTopic> subTopics,    // 4-6 个形成学习递进的子主题
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
        int subTopicIndex,       // 该项所属的子主题索引（-1 = 主题级别）
        String activityId        // 来自 PrePlannedItem；聊天驱动路径为 null
    ) {
        /** 向后兼容构造——subTopicIndex 默认 0，activityId 默认 null */
        public ResourcePlanItem(String type, String title, String difficulty,
            int estimatedMinutes, String format, List<String> keyPoints,
            String personalizationNote) {
            this(type, title, difficulty, estimatedMinutes, format, keyPoints,
                 personalizationNote, 0, null);
        }
        /** 向后兼容构造——activityId 默认 null */
        public ResourcePlanItem(String type, String title, String difficulty,
            int estimatedMinutes, String format, List<String> keyPoints,
            String personalizationNote, int subTopicIndex) {
            this(type, title, difficulty, estimatedMinutes, format, keyPoints,
                 personalizationNote, subTopicIndex, null);
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

    // -- 路由常量 --
    public static final String ROUTE_PLANNING = "PLANNING";
    public static final String ROUTE_PLAN_DRIVEN = "PLAN_DRIVEN";
    public static final String ROUTE_GENERATING = "GENERATING";
    public static final String ROUTE_REVIEWING = "REVIEWING";
    public static final String ROUTE_PUBLISHING = "PUBLISHING";
    public static final String ROUTE_REGENERATE = "REGENERATE";
    public static final String ROUTE_REPLAN = "REPLAN";
    public static final String ROUTE_SUCCEEDED = "SUCCEEDED";
    public static final String ROUTE_FAILED = "FAILED";
    public static final String ROUTE_FALLBACK = "FALLBACK";
}
