package com.learnthink.core.smart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Arrays;
import java.util.List;

/**
 * 收敛目标。
 * <p>写入系统提示词让 LLM 理解目标，同时提供代码兜底检查防止 LLM 忘记收敛。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConvergenceGoal(
    @JsonProperty List<String> requiredConcepts,
    @JsonProperty boolean requireTransferCheck,
    @JsonProperty int maxInteractions,
    @JsonProperty int minInteractions
) {
    /**
     * 生成 LLM 可读的目标描述。
     */
    public String description() {
        return "所有概念达到 MASTERED"
            + (requireTransferCheck ? "，并通过一次迁移检验" : "")
            + "。至少 " + minInteractions + " 轮，最多 " + maxInteractions + " 轮。";
    }

    /**
     * 代码兜底检查--不依赖 LLM 判断，防止 LLM 忘记收敛。
     */
    public boolean isHardConverged(SmartContext ctx) {
        if (ctx.totalTurns() >= maxInteractions) return true;
        boolean allMastered = requiredConcepts.stream()
            .allMatch(id -> ctx.conceptStatus().get(id) == ConceptStatus.MASTERED);
        return allMastered && ctx.totalTurns() >= minInteractions
            && (!requireTransferCheck || hasTransferCheck(ctx));
    }

    private boolean hasTransferCheck(SmartContext ctx) {
        return ctx.turns().stream()
            .anyMatch(t -> t.toolsUsed() != null
                && Arrays.asList(t.toolsUsed().split(",\\s*")).contains("challenge_transfer"));
    }

    /**
     * 创建默认收敛目标。
     */
    public static ConvergenceGoal defaults(List<String> requiredConcepts, int maxInteractions, int minInteractions) {
        return new ConvergenceGoal(requiredConcepts, true, maxInteractions, minInteractions);
    }
}
