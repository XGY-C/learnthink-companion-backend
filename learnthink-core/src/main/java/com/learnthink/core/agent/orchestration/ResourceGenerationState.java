package com.learnthink.core.agent.orchestration;

import java.time.Instant;
import java.util.*;

/**
 * The state that flows through the resource generation pipeline.
 * This is the "blackboard" — each agent reads from and writes to it.
 */
public class ResourceGenerationState {

    // -- Task identity --
    public String taskId;
    public String userId;
    public String courseId;
    public String topic;
    public List<String> resourceTypes = List.of("document", "exercise", "reading", "code", "mindmap");
    public int profileVersion;

    // -- Pipeline control --
    public String stage = "PENDING";
    public int percent;
    public String message;
    public String status = "PENDING"; // PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED
    public String errorCode;
    public String errorMessage;
    public boolean retryable;

    // -- PROFILING output --
    public ProfileSummary profileSummary;

    // -- RETRIEVING output --
    public List<SourceItem> mergedSources;
    public Map<String, List<SourceItem>> evidenceByType;
    public boolean forceLowConfidence;
    public int totalSources;

    // -- PLANNING output --
    public ResourcePlan resourcePlan;
    public int planRetryCount;

    // -- GENERATING output --
    public Map<String, GeneratedContent> artifacts = new HashMap<>();
    public Set<String> failedTypes = new HashSet<>();
    public int regenerateCount;

    // -- REVIEWING output --
    public Map<String, ReviewResult> reviewResults = new HashMap<>();
    public int reviewRetryCount;

    // -- PUBLISHING output --
    public String packId;
    public Set<String> publishedTypes = new HashSet<>();

    // -- Timing --
    public Map<String, Long> stageElapsedMs = new LinkedHashMap<>();
    public Instant createdAt = Instant.now();
    public Instant finishedAt;

    // -- Routing (set by nodes for GraphRunner) --
    String _nextRoute;

    // -- Progress broadcast hook (set by TaskGraphObserver before execution) --
    transient ProgressHook progressHook;
    transient TaskEventBroadcaster eventBroadcaster; // direct access for dedicated event types

    // -- Feedback data for retry loops (persisted across node revisits) --
    Set<String> regenerateTypes;       // types to regenerate on revisiting GENERATING
    Map<String, String> reviewFeedbackByType; // review feedback per type
    String planFeedback;               // feedback for replanning

    @FunctionalInterface
    public interface ProgressHook {
        void onProgress(String stage, int percent, String message, java.util.Map<String, Object> extra);
    }

    // -- Data classes --

    public record ProfileSummary(
        List<String> weakTop,
        List<String> style,
        int minutesPerDay,
        String goal,
        int dimensionCount
    ) {}

    public record SourceItem(
        String docId,
        String title,
        String chunkId,
        String quote,
        String locator,
        double relevance
    ) {}

    public record ResourcePlan(
        String topicOutline,
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
        String personalizationNote
    ) {}

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

    // -- Route constants for conditional edges --
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
