package com.learnthink.web.controller;

import com.learnthink.web.event.DefaultTaskEventBroadcaster;
import com.learnthink.core.agent.orchestration.TaskOrchestrator;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 任务API — SSE流式传输 + 任务生命周期管理
 */
@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskOrchestrator orchestrator;
    private final DefaultTaskEventBroadcaster broadcaster;

    /** 创建并启动资源生成任务 */
    @PostMapping("/generate")
    public Result<Map<String, String>> createTask(
        @RequestBody TaskOrchestrator.TaskCreateRequest req,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        String userId = UserContextUtil.getCurrentUserId();
        String taskId = orchestrator.createTask(userId, req, idempotencyKey);
        return Result.success(Map.of("taskId", taskId));
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

    /** 查询任务状态（轮询备用方案） */
    @GetMapping("/{taskId}")
    public Result<Map<String, Object>> status(@PathVariable String taskId) {
        // 从Redis热缓存中读取
        // TODO: 通过TaskRepository实现
        return Result.success(Map.of("taskId", taskId, "status", "check Events stream"));
    }
}
