package com.learnthink.core.directanswer.domain.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Step 1：答案卡（sticky top）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnswerCard(
    @JsonProperty(required = true) String answer,
    String unit,
    String format,
    String precision
) {}
