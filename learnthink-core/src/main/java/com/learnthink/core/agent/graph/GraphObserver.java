package com.learnthink.core.agent.graph;

/**
 * Observes state graph execution for tracing and monitoring.
 */
public interface GraphObserver<S> {
    /** Called before a node starts executing */
    default void onNodeStart(String nodeName, S state, int visitCount) {}

    /** Called after a node completes executing */
    default void onNodeComplete(String nodeName, S state, long elapsedMs) {}

    /** Called when a conditional router decides the next node */
    default void onRouting(String fromNode, String toNode, S state) {}

    /** Called when the graph completes */
    default void onGraphComplete(S finalState, long totalElapsedMs) {}

    /** Called when the graph fails */
    default void onGraphError(String nodeName, S state, Throwable error) {}
}
