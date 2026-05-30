package com.learnthink.core.agent.orchestration;

import java.util.List;

/**
 * 由外部规划器（如学习路径子计划）预先规划的单个资源。
 * 无需 LLM 重新规划。
 */
public record PrePlannedItem(
    String activityId,
    String title,
    String description,
    List<String> resourceTypes,
    List<String> knowledgePoints,
    String difficulty,
    int estimatedMinutes
) {}
