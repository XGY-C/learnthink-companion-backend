package com.learnthink.core.agent.orchestration;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 将任务事件广播到 Redis、数据库和 SSE 订阅者。
 * 实现处理三写模式：数据库（持久化）+ Redis（热数据）+ SSE（推送）。
 */
public interface TaskEventBroadcaster {

    /** 任务已接受并排队等待执行 */
    void taskAccepted(String taskId, Instant createdAt);

    /** 阶段转换事件 */
    void broadcastStage(String taskId, String stage, int percent, String message, Map<String, Object> stats);

    /** 通用事件（用于路由决策、错误等） */
    void broadcastEvent(String taskId, String eventType, Map<String, Object> payload);

    /** 单个资源已生成并可预览（content 为截断后的内容，最长 20000 字符） */
    void resourceReady(String taskId, String type, String title, String content, String confidence, int sourceCount);

    /** 审核标记了资源上的问题 */
    void reviewFlag(String taskId, String type, String action, String confidence, double citationCoverage);

    /** 任务完成（成功或部分成功） */
    void taskDone(String taskId, String status, String packId, int resourceCount, Set<String> failedTypes);

    /** 智能体思考链事件 — 4 层可见性模型（L1 身份 → L4 反思） */
    void agentThought(String taskId, String agentName, String agentRole,
                      String context, String observation, String thought,
                      String decision, String pipelineStage, String confidenceLevel);

    /** 任务因错误失败 */
    void taskFailed(String taskId, String errorCode, String message, boolean retryable);
}
