package com.learnthink.core.tutoring.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResolvedResources(
    @JsonProperty(required = true) Map<String, List<RetrievedChunk>> resources
) {
    public boolean isEmpty() {
        return resources == null || resources.isEmpty();
    }

    public List<RetrievedChunk> getForRequirement(String requirementId) {
        return resources != null ? resources.getOrDefault(requirementId, List.of()) : List.of();
    }
}
