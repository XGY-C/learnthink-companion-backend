package com.learnthink.core.directanswer.domain.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * SSE 内部用：分析结果（Phase1 输出）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisResult(
    @JsonProperty(required = true) String problemType,
    @JsonProperty(required = true) String subject,
    @JsonProperty(required = true) List<String> tags,
    @JsonProperty(required = true) boolean answerFirst
) {}
