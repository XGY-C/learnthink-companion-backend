package com.learnthink.core.directanswer.domain.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Step 2：题目分析（双栏对比）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemAnalysis(
    @JsonProperty(required = true) String problemType,
    @JsonProperty(required = true) String subject,
    @JsonProperty(required = true) List<String> tags,
    @JsonProperty(required = true) List<ConditionMapping> conditionMappings,
    @JsonProperty(required = true) boolean answerFirst,
    String overallSummary,
    String difficulty
) {}
