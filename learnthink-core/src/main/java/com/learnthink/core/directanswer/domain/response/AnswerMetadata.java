package com.learnthink.core.directanswer.domain.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 解答元数据。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnswerMetadata(
    @JsonProperty(required = true) String problemType,
    @JsonProperty(required = true) String subject,
    List<String> tags,
    String difficulty,
    String createdAt,
    String completedAt,
    int sectionCount
) {}
