package com.learnthink.core.agent.graph;

/**
 * 观察状态图执行，用于跟踪和监控。
 */
public interface GraphObserver<S> {
    /** 在节点开始执行之前调用 */
    default void onNodeStart(String nodeName, S state, int visitCount) {}

    /** 在节点完成执行之后调用 */
    default void onNodeComplete(String nodeName, S state, long elapsedMs) {}

    /** 当条件路由器决定下一个节点时调用 */
    default void onRouting(String fromNode, String toNode, S state) {}

    /** 当图执行完成时调用 */
    default void onGraphComplete(S finalState, long totalElapsedMs) {}

    /** 当图执行失败时调用 */
    default void onGraphError(String nodeName, S state, Throwable error) {}
}
