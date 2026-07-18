package com.learnthink.core.directanswer.domain.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Step 3：策略概览（思维叙述 + 步骤卡）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StrategyOverview(
    @JsonProperty(required = true) String narrative,
    @JsonProperty(required = true) List<ThinkingStep> steps,
    String keyInsight
) {}
