package com.learnthink.core.directanswer.domain.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * SSE 内部用：段落蓝图。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SectionBlueprint(
    @JsonProperty(required = true) String id,
    @JsonProperty(required = true) String title,
    @JsonProperty(required = true) int order
) {}
