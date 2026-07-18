package com.learnthink.core.smart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 每轮交互的摘要。
 * <p>由 LLM 在每轮结束后生成，存入 SmartContext.turns。
 * 不存完整对话（对话历史由 messages 表管理），只存摘要供 LLM 快速回顾。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TurnSummary(
    @JsonProperty int turn,
    @JsonProperty String summary,
    @JsonProperty String toolsUsed,
    @JsonProperty String conceptUpdate
) {
    public String toLine() {
        return "Turn " + turn + ": " + summary
            + (toolsUsed != null && !toolsUsed.isEmpty() ? " [工具: " + toolsUsed + "]" : "")
            + (conceptUpdate != null && !conceptUpdate.isEmpty() ? " [" + conceptUpdate + "]" : "");
    }
}
