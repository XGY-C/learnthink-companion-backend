package com.learnthink.core.directanswer.domain.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Step 5：方法总结（要点 + 口诀 + 条件规则）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MethodSummary(
    @JsonProperty(required = true) String coreMethod,
    @JsonProperty(required = true) List<String> keyPoints,
    String mnemonic,
    List<ConditionRule> conditionRules,
    List<TransferExample> transferExamples
) {}
