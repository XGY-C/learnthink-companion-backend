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

        // 注入进度钩子，使节点内的 advance() 调用能广播到 SSE
        if (s.progressHook == null) {
            s.progressHook = (stage, percent, message, extra) -> {
                Map<String, Object> stats = extra != null ? new java.util.LinkedHashMap<>(extra) : new java.util.LinkedHashMap<>();
                stats.put("nodeName", nodeName);
                stats.put("visitCount", visitCount);
                broadcaster.broadcastStage(s.taskId, stage, percent, message, stats);
            };
        }
        // 同时直接注入广播器以用于专用事件类型（resource.ready、review.flag）
        if (s.eventBroadcaster == null) {
            s.eventBroadcaster = broadcaster;
        }

        // 更新 Redis 状态
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

        // 通过 SSE 广播阶段进度
        broadcaster.broadcastStage(s.taskId, nodeName, s.percent, s.message,
            java.util.Map.of("elapsedMs", elapsedMs));

        // 记录计时
        s.stageElapsedMs.put(nodeName, elapsedMs);
    }

    @Override
    public void onRouting(String fromNode, String toNode, ResourceGenerationState s) {
        log.info("Routing: {} → {} (retryCount={})", fromNode, toNode, s.review.reviewRetryCount());

        // 记录决策以供回放
        Map<String, Object> decisionPayload = java.util.Map.of(
            "from", fromNode,
            "to", toNode,
            "reviewRetryCount", s.review.reviewRetryCount(),
            "planRetryCount", s.planning.planRetryCount(),
            "failedTypes", s.generation.failedTypes(),
            "forceLowConfidence", s.retrieval.forceLowConfidence()
        );

        broadcaster.broadcastEvent(s.taskId, "graph.route", decisionPayload);
    }

    @Override
    public void onGraphComplete(ResourceGenerationState s, long totalElapsedMs) {
        log.info("Graph complete: status={}, resources={}, failed={}, time={}ms",
            s.status, s.generation.artifacts().size(), s.generation.failedTypes().size(), totalElapsedMs);

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
