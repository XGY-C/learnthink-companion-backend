package com.learnthink.web.controller;

import com.learnthink.web.event.DefaultTaskEventBroadcaster;
import com.learnthink.core.agent.orchestration.TaskOrchestrator;
import com.learnthink.core.domain.entity.Task;
import com.learnthink.core.domain.entity.ResourcePack;
import com.learnthink.core.domain.entity.ResourceItem;
import com.learnthink.core.repository.TaskMapper;
import com.learnthink.core.repository.ResourcePackMapper;
import com.learnthink.core.repository.ResourceItemMapper;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 任务API — SSE流式传输 + 任务生命周期管理
 */
@Slf4j
@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskOrchestrator orchestrator;
    private final DefaultTaskEventBroadcaster broadcaster;
    private final TaskMapper taskMapper;
    private final ResourcePackMapper resourcePackMapper;
    private final ResourceItemMapper resourceItemMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 创建并启动资源生成任务 */
    @PostMapping("/generate")
    public Result<Map<String, String>> createTask(
        @RequestBody TaskOrchestrator.TaskCreateRequest req,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        String userId = UserContextUtil.getCurrentUserId();
        String taskId = orchestrator.createTask(userId, req, idempotencyKey);
        return Result.success(Map.of("taskId", taskId));
    }

    /** 查询当前用户的任务列表 */
    @GetMapping
    public Result<List<Map<String, Object>>> listTasks(
        @RequestParam(required = false) String courseId) {

        String userId = UserContextUtil.getCurrentUserId();
        List<Task> tasks;
        if (courseId != null && !courseId.isBlank()) {
            tasks = taskMapper.findByUserIdAndCourseId(userId, courseId);
        } else {
            tasks = taskMapper.findByUserId(userId);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Task task : tasks) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", task.getId());
            item.put("topic", task.getTopic());
            item.put("courseId", task.getCourseId());
            item.put("taskType", task.getTaskType());
            item.put("status", task.getStatus());
            item.put("stage", task.getStage());
            item.put("percent", task.getPercent());
            item.put("errorMessage", task.getErrorMessage());
            item.put("createdAt", formatTaskTime(task.getCreatedAt(), task.getStartedAt()));
            item.put("startedAt", task.getStartedAt() != null ? task.getStartedAt().atZone(ZoneId.systemDefault()).toInstant().toString() : null);
            item.put("finishedAt", task.getFinishedAt() != null ? task.getFinishedAt().atZone(ZoneId.systemDefault()).toInstant().toString() : null);

            // 从 requestedResourceTypes JSON 中解析资源类型
            List<String> resourceTypes = parseResourceTypes(task.getRequestedResourceTypes());
            item.put("resourceTypes", resourceTypes);

            // 针对运行中的任务，用 Redis 热数据覆盖 stage/percent
            if ("RUNNING".equals(task.getStatus())) {
                Map<Object, Object> redisStatus = redis.opsForHash().entries("task:" + task.getId() + ":status");
                if (!redisStatus.isEmpty()) {
                    item.put("stage", redisStatus.getOrDefault("stage", task.getStage()));
                    item.put("percent", redisStatus.getOrDefault("percent", task.getPercent()));
                }
            }

            // 查询已完成任务的资源包和资源数量
            if ("SUCCEEDED".equals(task.getStatus())) {
                try {
                    var pack = resourcePackMapper.selectOne(
                        new LambdaQueryWrapper<ResourcePack>()
                            .eq(ResourcePack::getTaskId, task.getId()));
                    if (pack != null) {
                        item.put("packId", pack.getId());
                        long readyCount = resourceItemMapper.selectCount(
                            new LambdaQueryWrapper<ResourceItem>()
                                .eq(ResourceItem::getPackId, pack.getId()));
                        item.put("resourceCount", (int) readyCount);
                    } else {
                        item.put("packId", null);
                        item.put("resourceCount", 0);
                    }
                } catch (Exception e) {
                    log.warn("Failed to look up pack for task {}: {}", task.getId(), e.getMessage());
                    item.put("packId", null);
                    item.put("resourceCount", 0);
                }
            } else {
                item.put("packId", null);
                item.put("resourceCount", 0);
            }

            result.add(item);
        }
        return Result.success(result);
    }

    private String formatTaskTime(LocalDateTime createdAt, LocalDateTime startedAt) {
        LocalDateTime t = createdAt != null ? createdAt : startedAt;
        if (t == null) return null;
        return t.atZone(ZoneId.systemDefault()).toInstant().toString();
    }

    private List<String> parseResourceTypes(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /** SSE事件流 — 前端订阅实时进度 */
    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String taskId, @RequestParam String token) {
        return broadcaster.subscribe(taskId);
    }

    /** 取消正在运行的任务 */
    @PostMapping("/{taskId}/cancel")
    public Result<Map<String, String>> cancel(@PathVariable String taskId) {
        boolean cancelled = orchestrator.cancelTask(taskId);
        if (cancelled) {
            return Result.success(Map.of("taskId", taskId, "status", "CANCELLED"));
        }
        return Result.error("TASK_ALREADY_FINAL", "Task already in final state");
    }

    /** 查询任务状态（轮询恢复进度用） */
    @GetMapping("/{taskId}")
    public Result<Map<String, Object>> status(@PathVariable String taskId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);

        // 始终从 MySQL 查询权威状态（status, stage, percent, error, pack_id）
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            return Result.error("TASK_NOT_FOUND", "Task not found");
        }
        result.put("status", task.getStatus());
        result.put("stage", task.getStage() != null ? task.getStage() : "");
        result.put("percent", task.getPercent() != null ? task.getPercent() : 0);
        result.put("topic", task.getTopic() != null ? task.getTopic() : "");
        String createdAtStr = formatTaskTime(task.getCreatedAt(), task.getStartedAt());
        result.put("created_at", createdAtStr != null ? createdAtStr : "");
        result.put("started_at", task.getStartedAt() != null ? task.getStartedAt().atZone(ZoneId.systemDefault()).toInstant().toString() : "");
        result.put("finished_at", task.getFinishedAt() != null ? task.getFinishedAt().atZone(ZoneId.systemDefault()).toInstant().toString() : "");
        result.put("error_code", task.getErrorCode());
        result.put("error_message", task.getErrorMessage());

        // 如有 Redis 热数据则覆盖（更实时的 stage/progress）
        Map<Object, Object> redisStatus = redis.opsForHash().entries("task:" + taskId + ":status");
        if (!redisStatus.isEmpty()) {
            result.put("stage", redisStatus.getOrDefault("stage", result.get("stage")));
            result.put("percent", redisStatus.getOrDefault("percent", result.get("percent")));
            result.put("updated_at", redisStatus.getOrDefault("updated_at", ""));
        }

        // 查询已完成任务的资源包
        try {
            var pack = resourcePackMapper.selectOne(
                new LambdaQueryWrapper<ResourcePack>()
                    .eq(ResourcePack::getTaskId, taskId));
            if (pack != null) {
                result.put("pack_id", pack.getId());
            }
        } catch (Exception e) {
            log.warn("Failed to look up pack for task {}: {}", taskId, e.getMessage());
        }

        return Result.success(result);
    }
}
