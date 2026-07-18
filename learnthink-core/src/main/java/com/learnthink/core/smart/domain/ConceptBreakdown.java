package com.learnthink.core.smart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 概念拆解（复用 v1 设计）。
 * <p>Phase1 分析阶段产出，描述一个子概念及其预期掌握标准。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConceptBreakdown(
    @JsonProperty(required = true) String id,
    @JsonProperty(required = true) String label,
    String description,
    String expectedAnswer,
    String difficulty,         // easy | medium | hard
    List<String> dependsOn
) {}
