package com.learnthink.core.agent.orchestration;

import com.learnthink.core.agent.graph.GraphObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;

/**
 * Bridges the StateGraph execution to the SSE event broadcasting system.
 * Every node transition in the graph becomes a {@code task.stage} event.
 */
public class TaskGraphObserver implements GraphObserver<ResourceGenerationState> {

    private static final Logger log = LoggerFactory.getLogger(TaskGraphObserver.class);

    private final ResourceGenerationState state;
    private final TaskEventBroadcaster broadcaster;
    private final StringRedisTemplate redis;

    public TaskGraphObserver(
        ResourceGenerationState state,
        TaskEventBroadcaster broadcaster,
        StringRedisTemplate redis) {
        this.state = state;
        this.broadcaster = broadcaster;
        this.redis = redis;
    }

    @Override
    public void onNodeStart(String nodeName, ResourceGenerationState s, int visitCount) {
        log.debug("Node start: {} (visit #{})", nodeName, visitCount);

        // Wire the progress hook so advance() calls inside nodes broadcast to SSE
        if (s.progressHook == null) {
            s.progressHook = (stage, percent, message, extra) -> {
                Map<String, Object> stats = extra != null ? new java.util.LinkedHashMap<>(extra) : new java.util.LinkedHashMap<>();
                stats.put("nodeName", nodeName);
                stats.put("visitCount", visitCount);
                broadcaster.broadcastStage(s.taskId, stage, percent, message, stats);
            };
        }
        // Also wire the broadcaster directly for dedicated event types (resource.ready, review.flag)
        if (s.eventBroadcaster == null) {
            s.eventBroadcaster = broadcaster;
        }

        // Update Redis status
        redis.opsForHash().putAll("task:" + s.taskId + ":status",
            java.util.Map.of(
                "status", "RUNNING",
                "stage", nodeName,
                "percent", String.valueOf(s.percent),
                "updated_at", java.time.Instant.now().toString()
            ));
        redis.expire("task:" + s.taskId + ":status", Duration.ofHours(24));
    }

    @Override
    public void onNodeComplete(String nodeName, ResourceGenerationState s, long elapsedMs) {
        log.debug("Node complete: {} ({}ms)", nodeName, elapsedMs);

        // Broadcast stage progress via SSE
        broadcaster.broadcastStage(s.taskId, nodeName, s.percent, s.message,
            java.util.Map.of("elapsedMs", elapsedMs));

        // Record timing
        s.stageElapsedMs.put(nodeName, elapsedMs);
    }

    @Override
    public void onRouting(String fromNode, String toNode, ResourceGenerationState s) {
        log.info("Routing: {} → {} (retryCount={})", fromNode, toNode, s.reviewRetryCount);

        // Log decision for replay
        Map<String, Object> decisionPayload = java.util.Map.of(
            "from", fromNode,
            "to", toNode,
            "reviewRetryCount", s.reviewRetryCount,
            "planRetryCount", s.planRetryCount,
            "failedTypes", s.failedTypes,
            "forceLowConfidence", s.forceLowConfidence
        );

        broadcaster.broadcastEvent(s.taskId, "graph.route", decisionPayload);
    }

    @Override
    public void onGraphComplete(ResourceGenerationState s, long totalElapsedMs) {
        log.info("Graph complete: status={}, resources={}, failed={}, time={}ms",
            s.status, s.artifacts.size(), s.failedTypes.size(), totalElapsedMs);

        broadcaster.broadcastEvent(s.taskId, "graph.complete", java.util.Map.of(
            "status", s.status,
            "totalElapsedMs", totalElapsedMs,
            "path", s.stageElapsedMs.keySet()
        ));
    }

    @Override
    public void onGraphError(String nodeName, ResourceGenerationState s, Throwable error) {
        log.error("Graph error at node {}: {}", nodeName, error.getMessage());
        broadcaster.broadcastEvent(s.taskId, "graph.error", java.util.Map.of(
            "nodeName", nodeName,
            "error", error.getMessage()
        ));
    }
}
