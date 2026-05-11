package com.learnthink.core.agent.orchestration;

import com.learnthink.core.agent.framework.AgentContext;
import com.learnthink.core.agent.framework.AgentObservation;
import com.learnthink.core.agent.framework.AgentResult;
import com.learnthink.core.agent.graph.GraphObserver;
import com.learnthink.core.agent.graph.GraphRunner;
import com.learnthink.core.service.TaskPersistenceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Entry point for multi-agent task orchestration.
 *
 * <p>Creates tasks, runs them asynchronously via the StateGraph, and broadcasts
 * progress events (SSE via {@link TaskEventBroadcaster}) at every stage.
 *
 * <h3>Key responsibilities:</h3>
 * <ul>
 *   <li>Idempotent task creation (via Redis Idempotency-Key)</li>
 *   <li>Thread pool management for async execution</li>
 *   <li>Graph execution with cancellation support</li>
 *   <li>Agent-level observability via {@link TaskGraphObserver}</li>
 * </ul>
 */
@Service
public class TaskOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TaskOrchestrator.class);

    private final ResourceGenerationGraph pipelineGraph;
    private final StringRedisTemplate redis;
    private final TaskEventBroadcaster broadcaster;
    private final TaskPersistenceService persistenceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Maps active taskId → GraphRunner for cancellation
    private final ConcurrentHashMap<String, GraphRunner<ResourceGenerationState>> activeRunners = new ConcurrentHashMap<>();

    private final ExecutorService taskExecutor = new ThreadPoolExecutor(
        2, 4, 60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(20),
        new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public TaskOrchestrator(
        ResourceGenerationGraph pipelineGraph,
        StringRedisTemplate redis,
        TaskEventBroadcaster broadcaster,
        TaskPersistenceService persistenceService) {
        this.pipelineGraph = pipelineGraph;
        this.redis = redis;
        this.broadcaster = broadcaster;
        this.persistenceService = persistenceService;
    }

    /**
     * Create and start a resource generation task.
     * Returns immediately with taskId — execution happens asynchronously.
     */
    public String createTask(String userId, TaskCreateRequest req) {
        return createTask(userId, req, null);
    }

    /**
     * Create and start a resource generation task with idempotency key.
     */
    public String createTask(String userId, TaskCreateRequest req, String idempotencyKey) {
        // Idempotency check
        if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
            String existing = redis.opsForValue().get("idem:" + userId + ":" + idempotencyKey);
            if (existing != null) {
                log.info("Idempotent request — returning existing task: {}", existing);
                return existing;
            }
        }

        String taskId = UUID.randomUUID().toString();

        // Store idempotency mapping
        if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
            redis.opsForValue().set("idem:" + userId + ":" + idempotencyKey, taskId, Duration.ofMinutes(5));
        }

        // Initialize state
        ResourceGenerationState state = new ResourceGenerationState();
        state.taskId = taskId;
        state.userId = userId;
        state.courseId = req.courseId();
        state.topic = req.topic();
        state.resourceTypes = req.resourceTypes() != null ? req.resourceTypes()
            : List.of("document", "exercise", "reading", "code", "mindmap");
        state.profileVersion = req.profileVersion();
        state.status = "PENDING";
        state.stage = "PENDING";
        state.percent = 0;
        state.createdAt = Instant.now();

        // Persist task to MySQL
        try {
            String resourceTypesJson = objectMapper.writeValueAsString(state.resourceTypes);
            persistenceService.createTask(userId, req.courseId(), "resource_generate",
                req.topic(), resourceTypesJson, req.profileVersion());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize resourceTypes", e);
        }
        // Redis persistence (fast path for SSE)
        persistTask(state);
        persistenceService.recordEvent(taskId, "task.accepted", Map.of("taskId", taskId, "createdAt", state.createdAt.toString()));
        broadcaster.taskAccepted(taskId, state.createdAt);

        // Execute asynchronously
        taskExecutor.submit(() -> executeTask(state));

        log.info("Task created: {} for user: {} topic: {}", taskId, userId, req.topic());
        return taskId;
    }

    /** Cancel a running task */
    public boolean cancelTask(String taskId) {
        redis.opsForValue().set("cancelled:" + taskId, "1", Duration.ofHours(1));
        GraphRunner<ResourceGenerationState> runner = activeRunners.get(taskId);
        if (runner != null) {
            runner.cancel();
            return true;
        }
        return false;
    }

    /** Get active runner count (for monitoring) */
    public int activeTaskCount() {
        return activeRunners.size();
    }

    // === Private ===

    private void executeTask(ResourceGenerationState state) {
        Instant start = Instant.now();
        log.info("Executing task: {} ({})", state.taskId, state.topic);

        try {
            // Build graph with observability
            GraphObserver<ResourceGenerationState> observer = new TaskGraphObserver(state, broadcaster, redis);
            GraphRunner<ResourceGenerationState> runner = pipelineGraph.build();
            runner.withObserver(observer)
                  .withNodeTimeout(Duration.ofMinutes(3));

            activeRunners.put(state.taskId, runner);

            ResourceGenerationState result = runner.execute(state);

            long totalMs = Duration.between(start, Instant.now()).toMillis();
            persistenceService.updateTaskStage(state.taskId, "PUBLISHING", 100,
                "SUCCEEDED".equals(result.status) ? "SUCCEEDED" : "FAILED");
            persistenceService.recordTaskDone(state.taskId, result.status, result.packId, result.artifacts.size());
            broadcaster.taskDone(state.taskId, result.status,
                result.packId, result.artifacts.size(), result.failedTypes);

            log.info("Task {} completed: status={}, resources={}, failed={}, time={}ms",
                state.taskId, result.status, result.artifacts.size(), result.failedTypes.size(), totalMs);

        } catch (Exception e) {
            log.error("Task {} failed unexpectedly: {}", state.taskId, e.getMessage(), e);
            persistenceService.failTask(state.taskId, "INTERNAL_ERROR", e.getMessage());
            persistenceService.recordEvent(state.taskId, "task.failed",
                Map.of("status", "FAILED", "errorCode", "INTERNAL_ERROR", "errorMessage", e.getMessage()));
            broadcaster.taskFailed(state.taskId, "INTERNAL_ERROR", e.getMessage(), false);
        } finally {
            activeRunners.remove(state.taskId);
        }
    }

    private void persistTask(ResourceGenerationState state) {
        // Persist to DB via task repository
        redis.opsForHash().putAll("task:" + state.taskId + ":status",
            Map.of("status", state.status, "stage", state.stage,
                   "percent", String.valueOf(state.percent)));
        redis.expire("task:" + state.taskId + ":status", Duration.ofHours(24));
    }

    // === Request DTO ===

    public record TaskCreateRequest(
        String courseId,
        String topic,
        List<String> resourceTypes,
        int profileVersion
    ) {}
}
