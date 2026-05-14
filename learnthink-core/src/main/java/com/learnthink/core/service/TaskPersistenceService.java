package com.learnthink.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.domain.entity.*;
import com.learnthink.core.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 统一的任务持久化服务 — 将所有"运行时"数据落 MySQL，确保可回放、可追溯。
 *
 * <p>覆盖范围：
 * <ul>
 *   <li>任务生命周期（tasks 表）</li>
 *   <li>任务事件日志（task_events 表）</li>
 *   <li>Agent 思考链（agent_thinking_traces 表）</li>
 *   <li>Agent 间协作消息（agent_messages 表）</li>
 *   <li>审校记录（review_records 表）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskPersistenceService {

    private final TaskMapper taskMapper;
    private final TaskEventMapper taskEventMapper;
    private final AgentThinkingTraceMapper thinkingTraceMapper;
    private final AgentMessageMapper agentMessageMapper;
    private final ReviewRecordMapper reviewRecordMapper;
    private final ProfileVersionMapper profileVersionMapper;
    private final ObjectMapper objectMapper;

    // ================================================================
    // Task lifecycle
    // ================================================================

    @Transactional
    public Task createTask(String taskId, String userId, String courseId, String taskType,
                           String topic, String resourceTypesJson, Integer profileVersion) {
        Task task = new Task();
        task.setId(taskId);
        task.setUserId(userId);
        task.setCourseId(courseId);
        task.setTaskType(taskType);
        task.setTopic(topic);
        task.setRequestedResourceTypes(resourceTypesJson);

        // tasks.profile_version_id stores profile_versions.id (CHAR(36)).
        // Orchestrator passes profileVersion (int). Resolve it to the version row id.
        if (profileVersion != null && profileVersion > 0) {
            try {
                ProfileVersion pv = profileVersionMapper.selectOne(
                    new LambdaQueryWrapper<ProfileVersion>()
                        .eq(ProfileVersion::getUserId, userId)
                        .eq(ProfileVersion::getCourseId, courseId)
                        .eq(ProfileVersion::getVersion, profileVersion)
                );
                if (pv != null) {
                    task.setProfileVersionId(pv.getId());
                } else {
                    log.debug("ProfileVersion not found, skip linking: userId={}, courseId={}, version={}",
                        userId, courseId, profileVersion);
                }
            } catch (Exception e) {
                log.warn("Failed to resolve profileVersionId, skip linking: userId={}, courseId={}, version={}",
                    userId, courseId, profileVersion, e);
            }
        }
        task.setStatus("PENDING");
        task.setPercent(0);
        taskMapper.insert(task);
        log.info("Task created: id={} type={}", task.getId(), taskType);
        return task;
    }

    @Transactional
    public void updateTaskStage(String taskId, String stage, int percent, String status) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) return;
        task.setStage(stage);
        task.setPercent(percent);
        task.setStatus(status);
        if ("RUNNING".equals(status) && task.getStartedAt() == null) {
            task.setStartedAt(LocalDateTime.now());
        }
        if ("SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
            task.setFinishedAt(LocalDateTime.now());
        }
        taskMapper.updateById(task);
    }

    @Transactional
    public void failTask(String taskId, String errorCode, String errorMessage) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) return;
        task.setStatus("FAILED");
        task.setErrorCode(errorCode);
        task.setErrorMessage(errorMessage);
        task.setFinishedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    // ================================================================
    // Task events (SSE replay source)
    // ================================================================

    @Transactional
    public TaskEvent recordEvent(String taskId, String eventType, Object payload) {
        TaskEvent event = new TaskEvent();
        event.setTaskId(taskId);
        event.setEventType(eventType);
        event.setPayloadJson(toJson(payload));
        taskEventMapper.insert(event);
        return event;
    }

    public void recordStageEvent(String taskId, String stage, int percent, String message, Map<String, Object> extra) {
        var payload = new java.util.HashMap<String, Object>();
        payload.put("stage", stage);
        payload.put("percent", percent);
        payload.put("message", message);
        if (extra != null) payload.putAll(extra);
        recordEvent(taskId, "task.stage", payload);
    }

    public void recordResourceReady(String taskId, String type, String title, String confidence, int sources) {
        var payload = Map.of("type", type, "title", title, "confidence", confidence, "sources", sources);
        recordEvent(taskId, "resource.ready", payload);
    }

    public void recordReviewFlag(String taskId, String type, String action, String confidence, double coverage) {
        var payload = Map.of("type", type, "action", action, "confidence", confidence, "coverage", coverage);
        recordEvent(taskId, "review.flag", payload);
    }

    public void recordTaskDone(String taskId, String status, String packId, int resourcesReady) {
        var payload = Map.of("status", status, "packId", packId, "resourcesReady", resourcesReady);
        recordEvent(taskId, "task.done", payload);
    }

    // ================================================================
    // Agent thinking traces
    // ================================================================

    @Transactional
    public AgentThinkingTrace recordThinkingTrace(String taskId, String agentName, String agentRole,
                                                   String phase, String context, String observation,
                                                   String thought, String decision, String confidenceLevel,
                                                   String trigger, String inResponseTo) {
        AgentThinkingTrace trace = new AgentThinkingTrace();
        trace.setTaskId(taskId);
        trace.setAgentName(agentName);
        trace.setAgentRole(agentRole);
        trace.setPhase(phase);
        trace.setContext(context);
        trace.setObservation(observation);
        trace.setThought(thought);
        trace.setDecision(decision);
        trace.setConfidenceLevel(confidenceLevel);
        trace.setTrigger(trigger);
        trace.setInResponseTo(inResponseTo);
        if (taskId == null || taskId.isBlank()) {
            log.warn("Skip thinking trace persistence: blank taskId (agentName={}, phase={})", agentName, phase);
            return trace;
        }
        // agent_thinking_traces.task_id has a strict FK to tasks.id.
        // Some flows (e.g., profile chat) may emit thinking events without a Task.
        // In that case, skip persistence instead of breaking the main user flow.
        if (taskMapper.selectById(taskId) == null) {
            log.debug("Skip thinking trace persistence: taskId not found in tasks (taskId={}, agentName={}, phase={})",
                taskId, agentName, phase);
            return trace;
        }

        thinkingTraceMapper.insert(trace);
        return trace;
    }

    /**
     * 简化版：从 AgentContext.Observation 自动提取写入
     */
    @Transactional
    public AgentThinkingTrace recordThinkingTrace(String taskId, String agentName, String agentRole,
                                                   String phase, String context, String observation,
                                                   String decision, String confidenceLevel) {
        return recordThinkingTrace(taskId, agentName, agentRole, phase, context, observation,
            null, decision, confidenceLevel, "autonomous", null);
    }

    // ================================================================
    // Agent collaboration messages
    // ================================================================

    @Transactional
    public AgentMessage recordAgentMessage(String taskId, String fromAgent, String toAgent,
                                            String agentRole, String action, String message, Object detail) {
        AgentMessage msg = new AgentMessage();
        msg.setTaskId(taskId);
        msg.setFromAgent(fromAgent);
        msg.setToAgent(toAgent);
        msg.setAgentRole(agentRole);
        msg.setAction(action);
        msg.setMessage(message);
        msg.setDetailJson(toJson(detail));
        agentMessageMapper.insert(msg);
        return msg;
    }

    // ================================================================
    // Review records
    // ================================================================

    @Transactional
    public ReviewRecord recordReview(String resourceItemId, String resourcePackId, String taskId,
                                      String result, String reasonsJson, double citationCoverage) {
        ReviewRecord record = new ReviewRecord();
        record.setResourceItemId(resourceItemId);
        record.setResourcePackId(resourcePackId);
        record.setTaskId(taskId);
        record.setResult(result);
        record.setReasonsJson(reasonsJson);
        record.setCitationCoverage(java.math.BigDecimal.valueOf(citationCoverage));
        reviewRecordMapper.insert(record);
        return record;
    }

    // ================================================================
    // helpers
    // ================================================================

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize to JSON", e);
            return "{}";
        }
    }
}
