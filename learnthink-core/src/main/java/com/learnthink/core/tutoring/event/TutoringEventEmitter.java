package com.learnthink.core.tutoring.event;

import com.learnthink.core.tutoring.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class TutoringEventEmitter {
    private static final Logger log = LoggerFactory.getLogger(TutoringEventEmitter.class);
    private final SseEmitter emitter;

    public TutoringEventEmitter(SseEmitter emitter) {
        this.emitter = emitter;
    }

    public void started(String sessionId) {
        send("tutoring.started", Map.of("sessionId", sessionId));
    }

    public void planMode(String mode) {
        send("tutoring.plan.mode", Map.of("mode", mode));
    }

    public void planClarify(String sessionId, ClarificationDecision decision, Clarification clarification) {
        send("tutoring.plan.clarify", Map.of(
            "sessionId", sessionId,
            "clarificationDecision", decision,
            "clarification", clarification));
    }

    public void planClarifyTimeout(String sessionId, int waitSeconds) {
        send("tutoring.plan.clarify_timeout", Map.of("sessionId", sessionId, "waitSeconds", waitSeconds));
    }

    public void waitingClarification(String sessionId) {
        send("tutoring.waiting_clarification", Map.of("sessionId", sessionId));
    }

    public void planAnalysis(QuestionAnalysis analysis) {
        send("tutoring.plan.analysis", analysis);
    }

    public void planPersonalization(TeachingPersonalization personalization) {
        send("tutoring.plan.personalization", Map.of(
            "strategy", personalization.strategy(),
            "depth", personalization.depth()));
    }

    public void planStructure(List<SectionBlueprint> sections) {
        List<Map<String, Object>> sectionList = sections.stream()
            .map(s -> {
                Map<String, Object> m = new java.util.HashMap<>();
                m.put("id", s.id());
                m.put("title", s.title());
                m.put("expandDefault", s.expandDefault());
                if (s.expectedDiagram() != null) {
                    m.put("expectedDiagram", Map.of(
                        "id", s.expectedDiagram().id(),
                        "type", s.expectedDiagram().type()
                    ));
                }
                return m;
            })
            .toList();
        send("tutoring.plan.structure", Map.of("sections", sectionList));
    }

    public void planResources(int count) {
        send("tutoring.plan.resources", Map.of("count", count));
    }

    public void planDone(String planId, String teachingThesis, int sectionCount, int resourceCount, int diagramCount) {
        send("tutoring.plan.done", Map.of(
            "planId", planId,
            "teachingThesis", teachingThesis,
            "sectionCount", sectionCount,
            "resourceCount", resourceCount,
            "diagramCount", diagramCount));
    }

    public void resourcesReady(int resolvedCount, int unavailableCount, List<String> unavailableIds) {
        send("tutoring.resources.ready", Map.of(
            "resolvedCount", resolvedCount,
            "unavailableCount", unavailableCount,
            "unavailableIds", unavailableIds));
    }

    public void textSectionStart(String sectionId, String title) {
        send("tutoring.text.section_start", Map.of("sectionId", sectionId, "title", title));
    }

    public void textChunk(String sectionId, String chunk) {
        send("tutoring.text.chunk", Map.of("sectionId", sectionId, "chunk", chunk));
    }

    public void textDiagramSpec(String diagramId, String sectionId, ExpectedDiagram spec) {
        send("tutoring.text.diagram_spec", Map.of(
            "diagramId", diagramId,
            "sectionId", sectionId,
            "spec", spec));
    }

    public void textSectionDone(String sectionId) {
        send("tutoring.text.section_done", Map.of("sectionId", sectionId));
    }

    public void diagramQueued(String diagramId, String sectionId) {
        send("tutoring.diagram.queued", Map.of("diagramId", diagramId, "sectionId", sectionId));
    }

    public void diagramDone(String diagramId, String sectionId, String url, String content,
                             int width, int height, String tool) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("diagramId", diagramId);
        data.put("sectionId", sectionId);
        if (url != null) data.put("url", url);
        if (content != null) data.put("content", content);
        data.put("width", width);
        data.put("height", height);
        data.put("tool", tool);
        send("tutoring.diagram.done", data);
    }

    public void diagramDegraded(String diagramId, String sectionId, String reason, String fallbackText) {
        send("tutoring.diagram.degraded", Map.of(
            "diagramId", diagramId, "sectionId", sectionId,
            "reason", reason, "fallbackText", fallbackText));
    }

    public void sectionRegenerated(String sectionId, String content) {
        send("tutoring.section.regenerated", Map.of("sectionId", sectionId, "content", content));
    }

    public void done(String sessionId) {
        send("tutoring.done", Map.of("sessionId", sessionId));
    }

    public void error(String code, String message, String phase, boolean retryable) {
        send("tutoring.error", Map.of(
            "code", code, "message", message, "phase", phase, "retryable", retryable));
    }

    private void send(String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            log.debug("SSE send failed (client disconnected): {}", e.getMessage());
        }
    }

    public void complete() {
        try { emitter.complete(); } catch (Exception ignored) {}
    }

    public SseEmitter getEmitter() { return emitter; }
}
