package com.learnthink.core.agent.impl;

import com.learnthink.core.agent.framework.*;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Master agent — globally routes and schedules all sub-agents.
 *
 * <h3>Design principles (from architecture doc §3):</h3>
 * <ul>
 *   <li><b>Rule-driven, not LLM-driven</b>: routing decisions are deterministic (read Context → match edge conditions).
 *       LLM routing would add latency, token cost, and non-determinism.</li>
 *   <li><b>Flow arbitration, not content arbitration</b>: OrchestratorAgent decides WHO runs next, not WHAT the answer is.
 *       Content disputes are resolved by "send back to upstream" rather than "I'll judge".</li>
 *   <li><b>Lightweight</b>: pure in-memory operations, no LLM calls. Actual latency < 5ms.</li>
 * </ul>
 *
 * <h3>Autonomy level: L3 (Judgment)</h3>
 * <p>OrchestratorAgent autonomously judges whether preconditions are met and decides flow direction.</p>
 */
@Component
public class OrchestratorAgent implements Agent<ResourceGenerationState, ResourceGenerationState> {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorAgent.class);

    @Override
    public String name() {
        return "OrchestratorAgent";
    }

    @Override
    public AgentResult<ResourceGenerationState> execute(ResourceGenerationState state, AgentContext ctx) {
        log.info("=== OrchestratorAgent START === stage={}, status={}", state.stage, state.status);
        Instant start = Instant.now();

        ctx.observation().onPrompt(name(), "Route decision for stage=" + state.stage,
            Map.of("status", state.status, "percent", state.percent));

        // OrchestratorAgent does not modify state — it only records the routing decision.
        // The actual routing is performed by StateGraph's conditional edges.
        // This agent exists to make the routing logic visible and traceable.

        String decision = determineNextPhase(state, ctx);
        log.info("Routing decision: {} -> {}", state.stage, decision);
        ctx.observation().onDecision(name(), decision,
            "Routing from " + state.stage + " based on state conditions");

        long elapsed = java.time.Duration.between(start, Instant.now()).toMillis();
        ctx.put("orchestrator_decision", decision);
        ctx.put("orchestrator_timestamp", Instant.now().toString());

        log.info("OrchestratorAgent: stage={} status={} decision={} (took {}ms)", 
                state.stage, state.status, decision, elapsed);

        return AgentResult.of(state, AgentResult.TokenUsage.ZERO, elapsed,
            Map.of("agent", name(), "decision", decision, "stage", state.stage));
    }

    /**
     * Deterministic routing logic — reads Context, matches conditions, returns next phase.
     * This is the "brain" of the multi-agent system, but it's a rule engine, not an LLM.
     */
    private String determineNextPhase(ResourceGenerationState state, AgentContext ctx) {
        String stage = state.stage != null ? state.stage : "PENDING";
        String status = state.status != null ? state.status : "PENDING";

        // Terminal states — no further routing
        if ("SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
            return "END";
        }

        // Entry point
        if ("PENDING".equals(status) || "PENDING".equals(stage)) {
            return "PROFILING";
        }

        return switch (stage) {
            case "PROFILING" -> {
                if (state.profileSummary != null && state.profileSummary.dimensionCount() >= 6) {
                    yield "RETRIEVING";
                }
                yield "FAIL"; // profile not ready
            }
            case "RETRIEVING" -> {
                if (Boolean.TRUE.equals(ctx.get("forceLowConfidence")) && state.totalSources == 0) {
                    yield "FALLBACK";
                }
                yield "PLANNING";
            }
            case "PLANNING" -> "GENERATING";
            case "GENERATING" -> "REVIEWING";
            case "REVIEWING" -> {
                // Check context for review results
                @SuppressWarnings("unchecked")
                var reviewResults = (Map<String, ResourceGenerationState.ReviewResult>)
                    ctx.memory().get("reviewResults");
                if (reviewResults == null || reviewResults.isEmpty()) {
                    yield "PUBLISHING";
                }
                long rejected = reviewResults.values().stream()
                    .filter(r -> r.action() == ResourceGenerationState.ReviewAction.RETRY).count();
                if (rejected == 0) {
                    yield "PUBLISHING";
                }
                int reviewRetryCount = ctx.get("reviewRetryCount") != null ?
                    (int) ctx.get("reviewRetryCount") : 0;
                if (reviewRetryCount >= 2) {
                    yield "PUBLISHING"; // max retries exhausted
                }
                yield "GENERATING"; // regenerate rejected
            }
            case "PUBLISHING" -> "END";
            case "FALLBACK" -> "GENERATING";
            default -> "END";
        };
    }

    @Override
    public boolean isRetryable() { return false; }
}
