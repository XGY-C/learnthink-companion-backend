package com.learnthink.core.agent.orchestration;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Broadcasts task events to Redis, DB, and SSE subscribers.
 * Implementation handles the three-write pattern: DB (persistent) + Redis (hot) + SSE (push).
 */
public interface TaskEventBroadcaster {

    /** Task accepted and queued for execution */
    void taskAccepted(String taskId, Instant createdAt);

    /** Stage transition event */
    void broadcastStage(String taskId, String stage, int percent, String message, Map<String, Object> stats);

    /** Generic event (for routing decisions, errors, etc.) */
    void broadcastEvent(String taskId, String eventType, Map<String, Object> payload);

    /** Individual resource generated and ready for preview */
    void resourceReady(String taskId, String type, String title, String confidence, int sourceCount);

    /** Review flagged an issue on a resource */
    void reviewFlag(String taskId, String type, String action, String confidence, double citationCoverage);

    /** Task completed (success or partial success) */
    void taskDone(String taskId, String status, String packId, int resourceCount, Set<String> failedTypes);

    /** Task failed with error */
    void taskFailed(String taskId, String errorCode, String message, boolean retryable);
}
