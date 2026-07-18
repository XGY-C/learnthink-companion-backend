package com.learnthink.core.directanswer.domain.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Step 6：错误预警（错误分类 + 变式题）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorWarning(
    @JsonProperty(required = true) String overview,
    @JsonProperty(required = true) List<ErrorCategory> categories,
    List<VariantQuestion> variantQuestions
) {}
