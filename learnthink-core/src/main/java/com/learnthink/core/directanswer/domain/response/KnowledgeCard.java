package com.learnthink.core.directanswer.domain.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Step 7：知识卡片（递归结构）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeCard(
    @JsonProperty(required = true) String id,
    @JsonProperty(required = true) String label,
    @JsonProperty(required = true) String level,
    @JsonProperty(required = true) String content,
    List<PrerequisiteAnchor> prerequisites
) {}
