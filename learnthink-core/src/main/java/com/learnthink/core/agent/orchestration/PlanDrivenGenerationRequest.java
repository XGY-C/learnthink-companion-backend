package com.learnthink.core.agent.orchestration;

import java.util.List;

/**
 * 将外部规划器（如学习路径子计划）预先规划的资源需求带入资源生成流水线。
 * 存在时，流水线跳过基于 LLM 的规划，直接使用这些条目。
 */
public record PlanDrivenGenerationRequest(
    String planId,
    String moduleId,
    String subPlanId,
    List<PrePlannedItem> items
) {}
