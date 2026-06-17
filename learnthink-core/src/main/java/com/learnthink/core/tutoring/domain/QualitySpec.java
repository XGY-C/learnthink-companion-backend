package com.learnthink.core.tutoring.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QualitySpec(
    @JsonProperty(required = true) List<String> keyClaims,
    @JsonProperty(required = true) boolean requireSourceCitation,
    @JsonProperty(required = true) List<String> forbiddenPhrases
) {}
