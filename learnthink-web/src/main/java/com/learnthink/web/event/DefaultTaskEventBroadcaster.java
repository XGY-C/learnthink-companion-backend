package com.learnthink.web.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.orchestration.TaskEventBroadcaster;
import com.learnthink.core.service.TaskPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class DefaultTaskEventBroadcaster implements TaskEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(DefaultTaskEventBroadcaster.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskPersistenceService persistenceService;

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public DefaultTaskEventBroadcaster(StringRedisTemplate redis, TaskPersistenceService persistenceService) {
        this.redis = redis;
        this.persistenceService = persistenceService;
    }

    @Override
    public void taskAccepted(String taskId, Instant createdAt) {
        broadcast(taskId, "task.accepted", Map.of("taskId", taskId, "createdAt", createdAt.toString()));
    }

    @Override
    public void broadcastStage(String taskId, String stage, int percent, String message, Map<String, Object> stats) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", stage);
        payload.put("percent", percent);
        payload.put("message", message);
        if (stats != null) payload.put("stats", stats);
        broadcast(taskId, "task.stage", payload);
    }

    @Override
    public void broadcastEvent(String taskId, String eventType, Map<String, Object> payload) {
        broadcast(taskId, eventType, payload);
    }

    @Override
    public void resourceReady(String taskId, String type, String title, String content, String confidence, int sourceCount) {
        broadcast(taskId, "resource.ready", Map.of(
            "type", type, "title", title, "content", content != null ? content : "",
            "confidence", confidence, "sources", sourceCount));
    }

    @Override
    public void reviewFlag(String taskId, String type, String action, String confidence, double citationCoverage) {
        broadcast(taskId, "review.flag", Map.of(
            "type", type, "action", action, "confidence", confidence, "coverage", citationCoverage));
    }

    @Override
    public void taskDone(String taskId, String status, String packId, int resourceCount, Set<String> failedTypes) {
        broadcast(taskId, "task.done", Map.of(
            "status", status, "packId", packId != null ? packId : "",
            "resourcesReady", resourceCount,
            "failedTypes", failedTypes != null ? failedTypes : Set.of()));
        
        // 延迟关闭 SSE 连接，给前端足够时间接收最终状态
        // 增加到 60 秒，避免前端仍在监听时过早关闭
        new Thread(() -> { 
            try { 
                Thread.sleep(60000); 
            } catch (InterruptedException ignored) {}
            closeSubscribers(taskId); 
        }).start();
    }

    @Override
    public void agentThought(String taskId, String agentName, String agentRole,
                              String context, String observation, String thought,
                              String decision, String pipelineStage, String confidenceLevel) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", UUID.randomUUID().toString());
        payload.put("agentName", agentName);
        payload.put("agentRole", agentRole);
        payload.put("phase", decision != null ? decision : "");
        payload.put("pipelineStage", pipelineStage != null ? pipelineStage : "");
        payload.put("context", context != null ? context : "");
        payload.put("observation", observation != null ? observation : "");
        payload.put("thought", thought != null ? thought : "");
        payload.put("decision", decision != null ? decision : "");
        payload.put("confidenceLevel", confidenceLevel != null ? confidenceLevel : "medium");
        payload.put("trigger", "autonomous");
        payload.put("timestamp", Instant.now().toString());
        broadcast(taskId, "agent.thought", payload);
        // 同时持久化到 MySQL，确保可追溯、可回放
        try {
            persistenceService.recordThinkingTrace(
                taskId, agentName, agentRole,
                "TASK_" + agentName.toUpperCase(),
                context, observation, thought, decision, confidenceLevel,
                "autonomous", null);
        } catch (Exception e) {
            log.warn("Failed to persist thinking trace for task {}: {}", taskId, e.getMessage());
        }
    }

    @Override
    public void taskFailed(String taskId, String errorCode, String message, boolean retryable) {
        broadcast(taskId, "task.failed", Map.of(
            "error", Map.of("code", errorCode, "message", message), "retryable", retryable));
        closeSubscribers(taskId);
    }

    public SseEmitter subscribe(String taskId) {
        SseEmitter emitter = new SseEmitter(600_000L);
        subscribers.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeSubscriber(taskId, emitter));
        emitter.onTimeout(() -> removeSubscriber(taskId, emitter));
        emitter.onError(e -> removeSubscriber(taskId, emitter));

        // 从 Redis 重放最近事件，让迟到连接的视图赶上进度
        replayRecentEvents(taskId, emitter);

        return emitter;
    }

    @SuppressWarnings("unchecked")
    private void replayRecentEvents(String taskId, SseEmitter emitter) {
        try {
            List<String> rawEvents = redis.opsForList().range("task:" + taskId + ":events", 0, -1);
            if (rawEvents == null || rawEvents.isEmpty()) return;

            // 反转：Redis 列表是新->旧（leftPush），重放时需要旧->新
            Collections.reverse(rawEvents);

            for (String json : rawEvents) {
                try {
                    Map<String, Object> event = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
                    String eventType = (String) event.get("eventType");
                    Map<String, Object> payload = (Map<String, Object>) event.get("payload");
                    if (eventType != null && payload != null) {
                        emitter.send(SseEmitter.event()
                            .name(eventType)
                            .id((String) event.get("eventId"))
                            .data(payload));
                    }
                } catch (Exception ignored) {
                    // 重放时跳过格式错误的事件
                }
            }
        } catch (Exception e) {
            log.debug("Event replay unavailable for task {}: {}", taskId, e.getMessage());
        }
    }

    private void broadcast(String taskId, String eventType, Map<String, Object> payload) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> event = Map.of(
            "eventId", eventId, "taskId", taskId, "eventType", eventType,
            "createdAt", Instant.now().toString(), "payload", payload);

        try {
            String json = mapper.writeValueAsString(event);
            redis.opsForList().leftPush("task:" + taskId + ":events", json);
            redis.opsForList().trim("task:" + taskId + ":events", 0, 199);
            redis.expire("task:" + taskId + ":events", Duration.ofHours(24));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event for task {}: {}", taskId, e.getMessage());
        }

        List<SseEmitter> emitters = subscribers.get(taskId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name(eventType).id(eventId).data(payload));
                } catch (IOException e) {
                    // IO 异常表示客户端已断开，正常清理即可
                    removeSubscriber(taskId, emitter);
                } catch (IllegalStateException e) {
                    // SseEmitter 已完成/关闭，正常情况
                    log.debug("SseEmitter already completed for task {}: {}", taskId, e.getMessage());
                    removeSubscriber(taskId, emitter);
                } catch (Exception e) {
                    // 其他异常记录为 DEBUG，避免日志污染
                    log.debug("SseEmitter send failed for task {} (removed): {}", taskId, e.getMessage());
                    removeSubscriber(taskId, emitter);
                }
            }
        }
    }

    private void removeSubscriber(String taskId, SseEmitter emitter) {
        List<SseEmitter> emitters = subscribers.get(taskId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) subscribers.remove(taskId);
        }
    }

    private void closeSubscribers(String taskId) {
        List<SseEmitter> emitters = subscribers.remove(taskId);
        if (emitters != null) emitters.forEach(e -> { try { e.complete(); } catch (Exception ignored) {} });
    }
}
