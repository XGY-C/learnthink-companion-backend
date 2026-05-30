package com.learnthink.core.agent.orchestration;

import com.learnthink.core.agent.runtime.AgentContext;
import com.learnthink.core.agent.runtime.AgentObservation;
import com.learnthink.core.agent.runtime.AgentResult;
import com.learnthink.core.agent.graph.GraphObserver;
import com.learnthink.core.agent.graph.GraphRunner;
import com.learnthink.core.domain.entity.SubPlan;
import com.learnthink.core.repository.SubPlanMapper;
import com.learnthink.core.service.TaskPersistenceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 多智能体任务编排的入口点。
 *
 * <p>创建任务，通过 StateGraph 异步执行，并在每个阶段广播进度事件（通过 {@link TaskEventBroadcaster} 的 SSE）。
 *
 * <h3>核心职责：</h3>
 * <ul>
 *   <li>幂等任务创建（通过 Redis Idempotency-Key）</li>
 *   <li>用于异步执行的线程池管理</li>
 *   <li>支持取消的图执行</li>
 *   <li>通过 {@link TaskGraphObserver} 实现 Agent 级别的可观测性</li>
 * </ul>
 */
@Service
public class TaskOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TaskOrchestrator.class);

    private final ResourceGenerationGraph pipelineGraph;
    private final StringRedisTemplate redis;
    private final TaskEventBroadcaster broadcaster;
    private final TaskPersistenceService persistenceService;
    private final SubPlanMapper subPlanMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 映射活跃的 taskId → GraphRunner，用于取消任务
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
        TaskPersistenceService persistenceService,
        SubPlanMapper subPlanMapper) {
        this.pipelineGraph = pipelineGraph;
        this.redis = redis;
        this.broadcaster = broadcaster;
        this.persistenceService = persistenceService;
        this.subPlanMapper = subPlanMapper;
    }

    /**
     * 创建并启动一个资源生成任务。
     * 立即返回 taskId——执行过程异步进行。
     */
    public String createTask(String userId, TaskCreateRequest req) {
        return createTask(userId, req, null);
    }

    /**
     * Create and start a resource generation task with idempotency key.
     */
    public String createTask(String userId, TaskCreateRequest req, String idempotencyKey) {
        // 幂等性检查
        if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
            String existing = redis.opsForValue().get("idem:" + userId + ":" + idempotencyKey);
            if (existing != null) {
                log.info("Idempotent request — returning existing task: {}", existing);
                return existing;
            }
        }

        String taskId = UUID.randomUUID().toString();

        // 存储幂等性映射
        if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
            redis.opsForValue().set("idem:" + userId + ":" + idempotencyKey, taskId, Duration.ofMinutes(5));
        }

        // 通过构造函数初始化状态
        // 优先从 items 推导 resourceTypes（去重），兼容旧 resourceTypes 字段
        List<String> effectiveResourceTypes = req.resourceTypes();
        if ((effectiveResourceTypes == null || effectiveResourceTypes.isEmpty())
                && req.items() != null && !req.items().isEmpty()) {
            effectiveResourceTypes = req.items().stream()
                .map(TaskCreateRequest.ResourceItem::type)
                .distinct()
                .toList();
        }
        ResourceGenerationState state = new ResourceGenerationState(
            taskId, userId, req.courseId(), req.topic(),
            effectiveResourceTypes,
            req.profileVersion());

        // 注入计划驱动上下文（提供预计划项时跳过 LLM 规划）
        if (req.planContext() != null) {
            state.planContext = req.planContext();
        }

        // 从聊天规划器恢复用户意图约束（弥补 SSE done 事件与 POST /tasks/generate 之间的间隙）
        // 仅用于聊天驱动路径（无预计划上下文）
        if (req.planContext() == null && req.chatId() != null) {
            try {
                String genMetaJson = redis.opsForValue().get("genmeta:" + req.chatId());
                if (genMetaJson != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> genMeta = objectMapper.readValue(genMetaJson, Map.class);
                    state.generationMeta = genMeta;
                    log.info("Restored generationMeta from Redis for chatId={}: {}", req.chatId(), genMeta);
                } else {
                    log.info("No generationMeta in Redis for chatId={} (may have expired or not set)", req.chatId());
                }
            } catch (Exception e) {
                log.warn("Failed to restore generationMeta from Redis for chatId={}: {}", req.chatId(), e.getMessage());
            }
        }

        // 聊天驱动路径：当 items 精确指定了每个资源时，转为 PrePlannedItem 跳过 CurriculumPlanner
        if (req.planContext() == null && req.items() != null && !req.items().isEmpty()) {
            String difficulty = state.generationMeta != null
                ? (String) state.generationMeta.getOrDefault("difficulty", "beginner")
                : "beginner";
            List<PrePlannedItem> preItems = req.items().stream()
                .map(item -> new PrePlannedItem(
                    null,                // activityId（聊天驱动无）
                    item.focus(),        // title ← focus 直接作为资源标题
                    item.focus(),        // description
                    List.of(item.type()), // resourceTypes（聊天驱动单类型）
                    List.of(),           // knowledgePoints（生成器自主拆分）
                    difficulty,
                    20                   // estimatedMinutes（默认值）
                ))
                .toList();
            state.planContext = new PlanDrivenGenerationRequest(null, null, null, preItems);
            log.info("Chat-driven items converted to planContext: {} items", preItems.size());
        }

        // 将任务持久化到 MySQL
        try {
            String resourceTypesJson = objectMapper.writeValueAsString(state.resourceTypes);
            persistenceService.createTask(taskId, userId, req.courseId(), "resource_generate",
                req.topic(), resourceTypesJson, req.profileVersion(), req.chatId());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize resourceTypes", e);
        }
        // Redis 持久化（SSE 快速路径）
        persistTask(state);
        persistenceService.recordEvent(taskId, "task.accepted", Map.of("taskId", taskId, "createdAt", state.createdAt.toString()));
        broadcaster.taskAccepted(taskId, state.createdAt);

        // 异步执行
        taskExecutor.submit(() -> executeTask(state));

        log.info("Task created: {} for user: {} topic: {}", taskId, userId, req.topic());
        log.info("Task details: courseId={}, resourceTypes={}, profileVersion={}", 
                req.courseId(), state.resourceTypes, req.profileVersion());
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
        log.info("=== Executing task: {} ({}) ===", state.taskId, state.topic);
        log.info("Task configuration: userId={}, courseId={}, resourceTypes={}, profileVersion={}",
                state.userId, state.courseId, state.resourceTypes, state.profileVersion);

        try {
            // 构建带可观测性的图
            log.info("Building StateGraph for task: {}", state.taskId);
            GraphObserver<ResourceGenerationState> observer = new TaskGraphObserver(state, broadcaster, redis);
            GraphRunner<ResourceGenerationState> runner = pipelineGraph.build();
            runner.withObserver(observer)
                  .withNodeTimeout(Duration.ofMinutes(3));

            activeRunners.put(state.taskId, runner);
            log.info("Starting graph execution for task: {}", state.taskId);

            ResourceGenerationState result = runner.execute(state);

            long totalMs = Duration.between(start, Instant.now()).toMillis();
            // 规范化最终状态：如果图执行完毕但状态仍为 RUNNING，则视为 SUCCEEDED（PUBLISHING 应已设置此状态）
            // 只有由 routeAfterGenerating/routeAfterReviewing 显式设置的 FAILED 才是最终状态
            String finalStatus = ("SUCCEEDED".equals(result.status)
                || "RUNNING".equals(result.status)) ? "SUCCEEDED" : result.status;
            log.info("Graph execution completed in {}ms", totalMs);
            log.info("Final state: status={}, artifacts={}, failedTypes={}",
                    result.status, result.generation.artifacts().size(), result.generation.failedTypes().size());
            persistenceService.updateTaskStage(state.taskId, "PUBLISHING", 100,
                finalStatus);
            persistenceService.recordTaskDone(state.taskId, finalStatus,
                result.publish.packId(), result.generation.artifacts().size());

            // 计划驱动生成：将生成的 packId 按 activityId + 成功类型精确回填
            if ("SUCCEEDED".equals(finalStatus) && result.publish.packId() != null
                    && state.planContext != null && state.planContext.subPlanId() != null) {
                backfillSubPlanPackId(state.planContext.subPlanId(),
                        result.publish.packId(), state.planContext,
                        result.generation.artifacts().keySet());
            }

            broadcaster.taskDone(state.taskId, finalStatus,
                result.publish.packId(), result.generation.artifacts().size(),
                result.generation.failedTypes());

            log.info("=== Task {} COMPLETED === status={}, resources={}, failed={}, time={}ms",
                state.taskId, finalStatus,
                result.generation.artifacts().size(), result.generation.failedTypes().size(), totalMs);

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

    /**
     * 计划驱动生成完成后，将生成的 packId 按 activityId 精确回填到子计划 JSON。
     * 只回填资源类型确实生成成功的活动，避免活动指向缺少对应类型内容的 pack。
     */
    @SuppressWarnings("unchecked")
    private void backfillSubPlanPackId(String subPlanId, String packId,
                                        PlanDrivenGenerationRequest planContext,
                                        Set<String> successfulTypes) {
        try {
            SubPlan subPlan = subPlanMapper.selectById(subPlanId);
            if (subPlan == null || subPlan.getSubPlanJson() == null) {
                log.warn("Cannot backfill packId: subPlan not found or empty, id={}", subPlanId);
                return;
            }

            // 从 planContext 构建 activityId 集合及其期望的资源类型
            Set<String> targetActivityIds = planContext != null
                ? planContext.items().stream()
                    .map(PrePlannedItem::activityId)
                    .filter(id -> id != null && !id.isBlank())
                    .collect(Collectors.toSet())
                : Set.of();

            // 防御：如果没有成功生成任何内容，则不要回填。
            // 否则活动将指向空资源包 → "内容加载失败"
            if (successfulTypes != null && successfulTypes.isEmpty()) {
                log.warn("No resources were successfully generated for subPlan={} — skipping backfill to avoid empty-pack link", subPlanId);
                return;
            }

            Map<String, Object> json = objectMapper.readValue(subPlan.getSubPlanJson(), LinkedHashMap.class);
            List<Map<String, Object>> activities = (List<Map<String, Object>>) json.get("activities");
            if (activities == null || activities.isEmpty()) return;

            boolean updated = false;
            for (Map<String, Object> act : activities) {
                // 收集需要回填的 resource entries（支持 act.resource 单数 和 act.resources 复数数组两种格式）
                List<Map<String, Object>> resList = new ArrayList<>();
                Map<String, Object> singleRes = (Map<String, Object>) act.get("resource");
                if (singleRes != null) resList.add(singleRes);
                List<Map<String, Object>> multiRes = (List<Map<String, Object>>) act.get("resources");
                if (multiRes != null) resList.addAll(multiRes);

                if (resList.isEmpty()) continue;

                for (Map<String, Object> res : resList) {
                    String source = (String) res.get("source");
                    String existingPackId = (String) res.get("resource_pack_id");
                    if (!"generated".equals(source)) continue;
                    if (existingPackId != null && !existingPackId.isBlank()) continue;

                    String actId = (String) act.get("activity_id");
                    String resType = (String) res.get("resource_type");

                    // 按 activityId 精确匹配（来自 planContext）
                    if (!targetActivityIds.isEmpty() && !targetActivityIds.contains(actId)) {
                        continue;
                    }

                    // 仅当该资源类型确实成功生成时才回填
                    if (successfulTypes != null && !successfulTypes.isEmpty()
                            && !successfulTypes.contains(resType)) {
                        log.info("Skipping backfill for activity {}: type={} was not successfully generated (successfulTypes={})",
                                actId, resType, successfulTypes);
                        continue;
                    }

                    res.put("resource_pack_id", packId);
                    res.put("generation_status", "ready");
                    updated = true;
                    log.info("Backfilled packId={} for generated activity {} (type={}) in subPlan={}",
                            packId, actId, resType, subPlanId);
                }
            }

            if (updated) {
                json.put("activities", activities);
                String updatedJson = objectMapper.writeValueAsString(json);
                subPlanMapper.updateSubPlan(subPlanId, updatedJson, subPlan.getVersion());
                log.info("SubPlan {} updated with generated packId={}", subPlanId, packId);
            }
        } catch (Exception e) {
            log.warn("Failed to backfill packId for subPlan {}: {}", subPlanId, e.getMessage());
        }
    }

    private void persistTask(ResourceGenerationState state) {
        // 通过任务仓库持久化到数据库
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
        int profileVersion,
        String chatId,
        PlanDrivenGenerationRequest planContext,
        List<ResourceItem> items
    ) {
        /** Backward-compatible constructor for chat-driven generation. */
        public TaskCreateRequest(String courseId, String topic, List<String> resourceTypes,
                                 int profileVersion, String chatId) {
            this(courseId, topic, resourceTypes, profileVersion, chatId, null, null);
        }

        /** Plan-driven batch generation constructor (no pre-planned items). */
        public TaskCreateRequest(String courseId, String topic, List<String> resourceTypes,
                                 int profileVersion, String chatId,
                                 PlanDrivenGenerationRequest planContext) {
            this(courseId, topic, resourceTypes, profileVersion, chatId, planContext, null);
        }

        /** Single resource item for chat-driven generation. */
        public record ResourceItem(
            String type,
            String focus
        ) {}
    }
}
