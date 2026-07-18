package com.learnthink.core.directanswer.domain.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Step 6 辅助：错误分类。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorCategory(
    @JsonProperty(required = true) String name,
    @JsonProperty(required = true) String description,
    List<ErrorExample> examples
) {}
