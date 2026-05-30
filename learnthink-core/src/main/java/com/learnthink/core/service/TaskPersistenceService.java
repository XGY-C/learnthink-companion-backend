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
import java.util.List;
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
    private final ResourcePackMapper resourcePackMapper;
    private final ResourceItemMapper resourceItemMapper;
    private final ObjectMapper objectMapper;

    // ================================================================
    // 任务生命周期
    // ================================================================

    @Transactional
    public Task createTask(String taskId, String userId, String courseId, String taskType,
                           String topic, String resourceTypesJson, Integer profileVersion,
                           String chatId) {
        Task task = new Task();
        task.setId(taskId);
        task.setUserId(userId);
        task.setCourseId(courseId);
        task.setTaskType(taskType);
        task.setTopic(topic);
        task.setRequestedResourceTypes(resourceTypesJson);
        task.setChatId(chatId);

        // tasks.profile_version_id 存储的是 profile_versions.id (CHAR(36))。
        // Orchestrator 传入 profileVersion (int)。在此解析为对应的版本行 id。
        // 如果指定了有效的画像版本号，则查询对应的画像版本记录并关联到当前任务
        if (profileVersion != null && profileVersion > 0) {
            try {
                // 根据 userId、courseId 和 version 精确查找画像版本 UUID
                ProfileVersion pv = profileVersionMapper.selectOne(
                    new LambdaQueryWrapper<ProfileVersion>()
                        .eq(ProfileVersion::getUserId, userId)
                        .eq(ProfileVersion::getCourseId, courseId)
                        .eq(ProfileVersion::getVersion, profileVersion)
                );
                if (pv != null) {
                    // 找到记录，关联画像版本 ID
                    task.setProfileVersionId(pv.getId());
                } else {
                    // 未找到对应版本，降级处理：仅记录调试日志，不阻断任务创建
                    log.debug("未找到 ProfileVersion，跳过关联：userId={}, courseId={}, version={}",
                        userId, courseId, profileVersion);
                }
            } catch (Exception e) {
                // 查询异常时容错处理：记录警告日志，确保画像版本关联失败不影响任务主流程
                log.warn("解析 profileVersionId 失败，跳过关联：userId={}, courseId={}, version={}",
                    userId, courseId, profileVersion, e);
            }
        }
        // 初始化任务状态为待处理，进度为 0
        task.setPercent(0);
        // 持久化任务记录到数据库
        taskMapper.insert(task);
        log.info("任务已创建：id={} type={}", task.getId(), taskType);
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

    /**
     * 查询任务状态（PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED），无记录返回 null
     */
    public String getTaskStatus(String taskId) {
        Task task = taskMapper.selectById(taskId);
        return task != null ? task.getStatus() : null;
    }

    public Task getTask(String taskId) {
        return taskMapper.selectById(taskId);
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
    // 任务事件（SSE 回放数据源）
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
            log.warn("跳过思考链持久化：taskId 为空（agentName={}, phase={}）", agentName, phase);
            return trace;
        }
        // agent_thinking_traces.task_id 对 tasks.id 有严格的外键约束。
        // 某些流程（如画像对话）可能会在没有 Task 的情况下发出思考事件。
        // 此时应跳过持久化，而不是中断主用户流程。
        if (taskMapper.selectById(taskId) == null) {
            log.debug("跳过思考链持久化：tasks 表中未找到 taskId（taskId={}, agentName={}, phase={}）",
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

    /**
     * 对话流思考链追踪：使用 chatId 而非 taskId，绕过 tasks FK 约束。
     * streamMessage 异步块中每个 ThinkingPhase 独立写入一行，确保 7 阶段全部持久化。
     *
     * @param roundNum 对话轮次（从 1 开始），用于历史消息重建思考链
     */
    @Transactional
    public AgentThinkingTrace recordChatThinkingTrace(String chatId, String agentName, String agentRole,
                                                       String phase, String context, String observation,
                                                       String thought, String decision, String confidenceLevel,
                                                       Integer roundNum) {
        AgentThinkingTrace trace = new AgentThinkingTrace();
        trace.setChatId(chatId);
        trace.setTaskId(null);
        trace.setAgentName(agentName);
        trace.setAgentRole(agentRole);
        trace.setPhase(phase);
        trace.setContext(context);
        trace.setObservation(observation);
        trace.setThought(thought);
        trace.setDecision(decision);
        trace.setConfidenceLevel(confidenceLevel);
        trace.setRoundNum(roundNum);
        trace.setTrigger("autonomous");
        trace.setInResponseTo(null);
        if (chatId == null || chatId.isBlank()) {
            log.warn("跳过对话思考链持久化：chatId 为空（agentName={}, phase={}）", agentName, phase);
            return trace;
        }
        thinkingTraceMapper.insert(trace);
        return trace;
    }

    /**
     * 按会话和轮次查询思考链记录（用于历史消息重建）
     */
    public List<AgentThinkingTrace> findTracesByChatIdAndRound(String chatId, Integer roundNum) {
        if (chatId == null || roundNum == null) return List.of();
        return thinkingTraceMapper.selectList(
            new LambdaQueryWrapper<AgentThinkingTrace>()
                .eq(AgentThinkingTrace::getChatId, chatId)
                .eq(AgentThinkingTrace::getRoundNum, roundNum)
                .orderByAsc(AgentThinkingTrace::getCreatedAt)
        );
    }

    /**
     * 查询指定会话的全部思考链记录（按创建时间排序），用于批量重建
     */
    public List<AgentThinkingTrace> findTracesByChatId(String chatId) {
        if (chatId == null || chatId.isBlank()) return List.of();
        return thinkingTraceMapper.selectList(
            new LambdaQueryWrapper<AgentThinkingTrace>()
                .eq(AgentThinkingTrace::getChatId, chatId)
                .orderByAsc(AgentThinkingTrace::getCreatedAt)
        );
    }

    // ================================================================
    // Agent 协作消息
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
    // 审校记录
    // ================================================================

    @Transactional
    public ReviewRecord recordReview(String resourceItemId, String resourcePackId, String taskId,
                                      String result, String reviewSummary, double citationCoverage) {
        ReviewRecord record = new ReviewRecord();
        record.setResourceItemId(resourceItemId);
        record.setResourcePackId(resourcePackId);
        record.setTaskId(taskId);
        record.setResult(result);
        // reviewSummary 是原始文本；序列化为 JSON 字符串以存入 MySQL JSON 列
        record.setReasonsJson(toJson(reviewSummary));
        record.setCitationCoverage(java.math.BigDecimal.valueOf(citationCoverage));
        reviewRecordMapper.insert(record);
        return record;
    }

    // ================================================================
    // 资源包持久化
    // ================================================================

    @Transactional
    public String saveResourcePack(String packId, String userId, String courseId, String topic,
                                    String taskId, String profileVersionId, List<String> pushReasons) {
        ResourcePack pack = new ResourcePack();
        pack.setId(packId);
        pack.setUserId(userId);
        pack.setCourseId(courseId);
        pack.setTopic(topic);
        pack.setTaskId(taskId);
        pack.setGeneratedFromProfileVersionId(profileVersionId);
        pack.setPushReasonJson(toJson(pushReasons));
        pack.setCreatedAt(LocalDateTime.now());
        resourcePackMapper.insert(pack);
        log.info("资源包已保存：id={}, topic={}", packId, topic);
        return packId;
    }

    @Transactional
    public String saveResourceItem(String itemId, String packId, String taskId,
                                    String type, String title, String content,
                                    String mimeType, String confidence,
                                    List<Map<String, Object>> sources,
                                    String reviewStatus, String reviewSummary,
                                    int subTopicIndex) {
        ResourceItem item = new ResourceItem();
        item.setId(itemId);
        item.setPackId(packId);
        item.setTaskId(taskId);
        item.setType(type);
        item.setTitle(title);
        item.setStatus("ready");
        String contentRef = "resources/" + taskId + "/" + type;
        item.setContentRef(contentRef);
        item.setContentMime(mimeType);
        item.setConfidence(confidence);
        item.setSourcesJson(toJson(sources));
        item.setReviewStatus(reviewStatus);
        item.setReviewSummary(reviewSummary);
        item.setSubtopicIndex(subTopicIndex);
        // 将完整内容存储在 metadata_json 中
        Map<String, String> meta = new java.util.HashMap<>();
        meta.put("content", content);
        meta.put("charCount", String.valueOf(content.length()));
        item.setMetadataJson(toJson(meta));
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        resourceItemMapper.insert(item);
        log.info("资源项已保存：type={}, title={}, confidence={}", type, title, confidence);
        return itemId;
    }

    // ================================================================
    // 查询辅助方法
    // ================================================================

    /**
     * 查询用户+课程下处于活跃状态的 plan_generate 任务（用于幂等性检查）
     */
    public List<Task> findActivePlanTasks(String userId, String courseId) {
        return taskMapper.selectList(
            new LambdaQueryWrapper<Task>()
                .eq(Task::getUserId, userId)
                .eq(Task::getCourseId, courseId)
                .eq(Task::getTaskType, "plan_generate")
                .in(Task::getStatus, "PENDING", "RUNNING"));
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    /**
     * 根据版本号解析画像版本 UUID。
     */
    public String resolveProfileVersionId(String userId, String courseId, int profileVersion) {
        try {
            ProfileVersion pv = profileVersionMapper.selectOne(
                new LambdaQueryWrapper<ProfileVersion>()
                    .eq(ProfileVersion::getUserId, userId)
                    .eq(ProfileVersion::getCourseId, courseId)
                    .eq(ProfileVersion::getVersion, profileVersion));
            return pv != null ? pv.getId() : null;
        } catch (Exception e) {
            log.warn("解析画像版本失败：userId={}, courseId={}, version={}",
                userId, courseId, profileVersion, e);
            return null;
        }
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("序列化为 JSON 失败", e);
            return "{}";
        }
    }
}
