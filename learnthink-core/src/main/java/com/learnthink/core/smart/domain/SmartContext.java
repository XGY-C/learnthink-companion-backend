package com.learnthink.core.smart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Smart 模式上下文。
 * <p>存储在 Redis（SmartStateStore），每轮对话后更新。
 * 序列化为 JSON 注入系统提示词，LLM 据此理解和决策。</p>
 *
 * <h3>不可变性与工具更新</h3>
 * <p>SmartContext 是 record（不可变），但 {@code update_concept_status} 等工具在流式输出过程中
 * 需要更新状态。通过 {@code AtomicReference<SmartContext>} 统一持有，
 * 工具和 doFinally 都读写 ctxRef。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SmartContext(
    @JsonProperty String sessionId,
    @JsonProperty String userId,
    @JsonProperty String courseId,

    // -- 概念拆解（来自 Phase1）--
    @JsonProperty List<ConceptBreakdown> concepts,
    @JsonProperty Map<String, ConceptStatus> conceptStatus,

    // -- 学习画像摘要 --
    @JsonProperty String profileSummary,

    // -- 收敛目标 --
    @JsonProperty ConvergenceGoal goal,

    // -- 交互历史（LLM 可读的摘要）--
    @JsonProperty List<TurnSummary> turns,
    @JsonProperty int totalTurns,

    // -- 状态标记 --
    @JsonProperty boolean converged,
    @JsonProperty String convergenceSummary
) {
    /**
     * 序列化为系统提示词的可读段落。
     * 这段文字注入系统提示词，让 LLM 理解当前教学状态。
     */
    public String toContextBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前教学状态\n\n");

        sb.append("### 概念掌握度\n");
        if (concepts != null) {
            for (ConceptBreakdown c : concepts) {
                ConceptStatus s = conceptStatus == null
                    ? ConceptStatus.UNVERIFIED
                    : conceptStatus.getOrDefault(c.id(), ConceptStatus.UNVERIFIED);
                sb.append("- ").append(c.label())
                  .append(" (").append(c.difficulty() != null ? c.difficulty() : "unknown").append(")")
                  .append(": ").append(s.label())
                  .append("\n");
            }
        }

        sb.append("\n### 学习画像\n");
        sb.append(profileSummary != null ? profileSummary : "（暂无画像信息）").append("\n");

        sb.append("\n### 交互历史（最近 5 轮）\n");
        if (turns != null && !turns.isEmpty()) {
            int start = Math.max(0, turns.size() - 5);
            for (int i = start; i < turns.size(); i++) {
                sb.append(turns.get(i).toLine()).append("\n");
            }
        } else {
            sb.append("（首轮交互）\n");
        }

        sb.append("\n### 收敛目标\n");
        sb.append(goal != null ? goal.description() : "所有概念达到 MASTERED").append("\n");

        return sb.toString();
    }

    /** 便捷方法：返回更新了 conceptStatus 的新实例（record 不可变） */
    public SmartContext withConceptStatus(Map<String, ConceptStatus> newStatus) {
        return new SmartContext(sessionId, userId, courseId, concepts, newStatus,
            profileSummary, goal, turns, totalTurns, converged, convergenceSummary);
    }

    /** 便捷方法：返回更新了 profileSummary 的新实例 */
    public SmartContext withProfileSummary(String newProfile) {
        return new SmartContext(sessionId, userId, courseId, concepts, conceptStatus,
            newProfile, goal, turns, totalTurns, converged, convergenceSummary);
    }

    /** 便捷方法：返回更新了 turns 和 totalTurns 的新实例 */
    public SmartContext withTurn(List<TurnSummary> newTurns, int newTotalTurns) {
        return new SmartContext(sessionId, userId, courseId, concepts, conceptStatus,
            profileSummary, goal, newTurns, newTotalTurns, converged, convergenceSummary);
    }

    /** 便捷方法：返回标记为已收敛的新实例 */
    public SmartContext withConverged(String summary) {
        return new SmartContext(sessionId, userId, courseId, concepts, conceptStatus,
            profileSummary, goal, turns, totalTurns, true, summary);
    }

    /**
     * 初始化 SmartContext。
     */
    public static SmartContext initialize(String sessionId, String userId, String courseId,
                                          List<ConceptBreakdown> concepts, String profileSummary,
                                          ConvergenceGoal goal) {
        Map<String, ConceptStatus> statusMap = new HashMap<>();
        if (concepts != null) {
            for (ConceptBreakdown c : concepts) {
                statusMap.put(c.id(), ConceptStatus.UNVERIFIED);
            }
        }
        return new SmartContext(
            sessionId, userId, courseId,
            concepts, statusMap,
            profileSummary,
            goal,
            List.of(), 0,
            false, null
        );
    }
}
