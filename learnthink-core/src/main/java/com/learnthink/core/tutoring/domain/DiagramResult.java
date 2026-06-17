package com.learnthink.core.tutoring.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagramResult(
    @JsonProperty(required = true) String diagramId,
    @JsonProperty(required = true) String status,
    String url,
    String content,
    Integer width,
    Integer height,
    String tool,
    String fallbackText,
    byte[] bytes
) {
    public static DiagramResult generated(String diagramId, String url, int width, int height, String tool) {
        return new DiagramResult(diagramId, "generated", url, null, width, height, tool, null, null);
    }

    public static DiagramResult generatedInline(String diagramId, String content, int width, int height, String tool) {
        return new DiagramResult(diagramId, "generated", null, content, width, height, tool, null, null);
    }

    public static DiagramResult degraded(String diagramId, String reason, String fallbackText, String tool) {
        return new DiagramResult(diagramId, "degraded", null, null, null, null, tool, fallbackText, null);
    }

    public static DiagramResult failed(String diagramId, String reason) {
        return new DiagramResult(diagramId, "failed", null, null, null, null, null, reason, null);
    }
}
