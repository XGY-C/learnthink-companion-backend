package com.learnthink.core.tutoring.event;

import com.learnthink.core.tutoring.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TutoringEventEmitter {
    private static final Logger log = LoggerFactory.getLogger(TutoringEventEmitter.class);
    private final SseEmitter emitter;

    /** 收集 ReAct 思考过程，供持久化使用 */
    private final java.util.List<java.util.Map<String, Object>> reactThoughts = new java.util.ArrayList<>();

    /** 心跳调度器 */
    private ScheduledExecutorService heartbeatScheduler;
    private final AtomicBoolean heartbeatActive = new AtomicBoolean(false);

    public TutoringEventEmitter(SseEmitter emitter) {
        this.emitter = emitter;
        startHeartbeat();
    }

    public java.util.List<java.util.Map<String, Object>> getReactThoughts() {
        return reactThoughts;
    }

    /**
     * Phase 5 H3: SSE 心跳 — 每 30s 发送空注释，防止代理/Nginx 断开连接。
     */
    private void startHeartbeat() {
        if (heartbeatActive.get()) return;
        heartbeatActive.set(true);
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tutoring-sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException e) {
                log.debug("Tutoring SSE heartbeat failed (client disconnected)");
                stopHeartbeat();
            } catch (Exception e) {
                log.debug("Tutoring SSE heartbeat error: {}", e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * 在 done/error/complete 时停止心跳。
     */
    private void stopHeartbeat() {
        heartbeatActive.set(false);
        if (heartbeatScheduler != null && !heartbeatScheduler.isShutdown()) {
            heartbeatScheduler.shutdown();
        }
    }

    public void started(String sessionId) {
        send("tutoring.started", Map.of("sessionId", sessionId));
    }

    /** 发送 ReAct 思考过程（ Thought + Action ） */
    public void reactThought(int iteration, String thought, String action) {
        send("tutoring.react.thought", Map.of(
            "iteration", iteration,
            "thought", thought,
            "action", action));
        // 收集用于持久化
        java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("iteration", iteration);
        entry.put("thought", thought);
        entry.put("action", action);
        reactThoughts.add(entry);
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

    // ===== Guided Mode Events =====

    public void guidedStepStart(String stepId, String stage, String title,
                                int stepIndex, int totalSteps) {
        send("tutoring.guided.step_start", Map.of(
            "stepId", stepId, "stage", stage, "title", title,
            "stepIndex", stepIndex, "totalSteps", totalSteps));
    }

    public void guidedGuidanceChunk(String stepId, String chunk) {
        send("tutoring.guided.guidance_chunk", Map.of("stepId", stepId, "chunk", chunk));
    }

    public void guidedQuestion(String stepId, String question) {
        send("tutoring.guided.question", Map.of("stepId", stepId, "question", question));
    }

    public void guidedWaitingAnswer(String stepId, int attempt, int maxAttempts, boolean allowReveal) {
        send("tutoring.guided.waiting_answer", Map.of(
            "stepId", stepId, "attempt", attempt,
            "maxAttempts", maxAttempts, "allowReveal", allowReveal));
    }

    public void guidedFeedback(String stepId, String feedback, String hint,
                               boolean allowReveal, boolean canAdvance) {
        send("tutoring.guided.feedback", Map.of(
            "stepId", stepId, "feedback", feedback, "hint", hint,
            "allowReveal", allowReveal, "canAdvance", canAdvance));
    }

    public void guidedStepDone(String stepId, String evaluation, long timeSpentMs) {
        send("tutoring.guided.step_done", Map.of(
            "stepId", stepId, "evaluation", evaluation, "timeSpentMs", timeSpentMs));
    }

    public void guidedRevealed(String stepId, String answer, String explanation) {
        send("tutoring.guided.revealed", Map.of(
            "stepId", stepId, "answer", answer, "explanation", explanation));
    }

    public void guidedSummaryChunk(String chunk) {
        send("tutoring.guided.summary", Map.of("chunk", chunk));
    }

    public void done(String sessionId) {
        stopHeartbeat();
        send("tutoring.done", Map.of("sessionId", sessionId));
    }

    public void error(String code, String message, String phase, boolean retryable) {
        stopHeartbeat();
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
        stopHeartbeat();
        try { emitter.complete(); } catch (Exception ignored) {}
    }

    public SseEmitter getEmitter() { return emitter; }
}
