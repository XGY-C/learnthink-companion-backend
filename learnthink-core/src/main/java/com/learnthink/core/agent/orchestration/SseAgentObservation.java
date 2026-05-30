package com.learnthink.core.agent.orchestration;

import com.learnthink.core.agent.runtime.AgentObservation;

/**
 * Bridges agent observations to the SSE event broadcaster so the frontend
 * "Agent 思考链" panel can show real-time agent decisions.
 *
 * <p>Every {@code onDecision} call becomes an {@code agent.thought} SSE event;
 * {@code onError} calls are also forwarded for visibility.</p>
 */
public class SseAgentObservation implements AgentObservation {

    private final String taskId;
    private final TaskEventBroadcaster broadcaster;

    public SseAgentObservation(String taskId, TaskEventBroadcaster broadcaster) {
        this.taskId = taskId;
        this.broadcaster = broadcaster;
    }

    @Override
    public void onDecision(String agentName, String decision, String reason) {
        if (broadcaster != null) {
            broadcaster.agentThought(
                taskId, agentName, agentName,
                "",           // context — filled by broadcaster with agentName
                reason,       // observation — what the agent observed / the reason
                reason,       // thought — the agent's reasoning
                decision,     // decision — e.g. START, RETRIEVE, ITERATION, COMPLETE
                "high");
        }
    }

    @Override
    public void onError(String agentName, Throwable error) {
        if (broadcaster != null) {
            broadcaster.agentThought(
                taskId, agentName, agentName,
                "",
                error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName(),
                "",
                "ERROR",
                "low");
        }
    }
}
