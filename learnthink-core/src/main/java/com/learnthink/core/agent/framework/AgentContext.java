package com.learnthink.core.agent.framework;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Context passed to every agent during execution.
 * Contains task identity, shared memory (the "blackboard"), budget, and observability hooks.
 */
public class AgentContext {
    private final String taskId;
    private final String userId;
    private final String courseId;
    private final Map<String, Object> memory;
    private final AgentObservation observation;
    private final TokenBudget budget;
    private final boolean cancelled;

    private AgentContext(Builder builder) {
        this.taskId = builder.taskId;
        this.userId = builder.userId;
        this.courseId = builder.courseId;
        this.memory = builder.memory != null ? builder.memory : new ConcurrentHashMap<>();
        this.observation = builder.observation != null ? builder.observation : AgentObservation.NOOP;
        this.budget = builder.budget != null ? builder.budget : TokenBudget.unlimited();
        this.cancelled = builder.cancelled;
    }

    // -- Memory access (the blackboard) --

    @SuppressWarnings("unchecked")
    public <T> T get(String key) { return (T) memory.get(key); }

    public void put(String key, Object value) { memory.put(key, value); }

    public boolean has(String key) { return memory.containsKey(key); }

    // -- Getters --

    public String taskId() { return taskId; }
    public String userId() { return userId; }
    public String courseId() { return courseId; }
    public AgentObservation observation() { return observation; }
    public TokenBudget budget() { return budget; }
    public boolean isCancelled() { return cancelled; }
    public Map<String, Object> memory() { return memory; }

    public static Builder builder(String taskId, String userId) {
        return new Builder(taskId, userId);
    }

    public static class Builder {
        private final String taskId;
        private final String userId;
        private String courseId;
        private Map<String, Object> memory;
        private AgentObservation observation;
        private TokenBudget budget;
        private boolean cancelled;

        private Builder(String taskId, String userId) {
            this.taskId = taskId;
            this.userId = userId;
        }

        public Builder courseId(String v) { this.courseId = v; return this; }
        public Builder memory(Map<String, Object> v) { this.memory = v; return this; }
        public Builder observation(AgentObservation v) { this.observation = v; return this; }
        public Builder budget(TokenBudget v) { this.budget = v; return this; }
        public Builder cancelled(boolean v) { this.cancelled = v; return this; }
        public AgentContext build() { return new AgentContext(this); }
    }
}
