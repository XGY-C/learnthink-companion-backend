package com.learnthink.core.directanswer.domain.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Step 4↔7 锚点：推理步骤到知识卡片的交叉引用。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PrerequisiteAnchor(
    @JsonProperty(required = true) String stepId,
    @JsonProperty(required = true) String knowledgeCardId,
    String knowledgeLabel
) {}
