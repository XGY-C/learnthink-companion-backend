package com.learnthink.core.directanswer.domain.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Step 4：推理链（时间轴 + 节点）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReasoningChain(
    @JsonProperty(required = true) String title,
    @JsonProperty(required = true) List<ReasoningStep> steps,
    List<PrerequisiteAnchor> prerequisiteAnchors
) {}
