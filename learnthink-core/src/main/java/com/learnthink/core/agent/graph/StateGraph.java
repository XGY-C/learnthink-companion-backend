package com.learnthink.core.agent.graph;

import java.util.*;
import java.util.function.Function;

/**
 * 用于多智能体编排的通用状态图。
 * 支持条件路由、循环（反馈循环）和可观察性。
 *
 * @param <S> 在图中流转的状态类型
 */
public class StateGraph<S> {

    private final Map<String, GraphNode<S>> nodes = new LinkedHashMap<>();
    private final Map<String, List<GraphEdge<S>>> edges = new HashMap<>();
    private String entryPoint;
    private int maxCycles = 3;

    public StateGraph<S> addNode(String name, Function<S, S> processor) {
        return addNode(name, processor, null);
    }

    public StateGraph<S> addNode(String name, Function<S, S> processor, String description) {
        nodes.put(name, new GraphNode<>(name, processor, description));
        edges.putIfAbsent(name, new ArrayList<>());
        return this;
    }

    /** 无条件转移：from → to */
    public StateGraph<S> addEdge(String from, String to) {
        edges.computeIfAbsent(from, k -> new ArrayList<>())
             .add(new GraphEdge<>(from, to, null, null));
        return this;
    }

    /**
     * 条件转移：执行完 'from' 后，router 函数根据状态决定下一个节点。
     */
    public StateGraph<S> addConditionalEdge(String from, Function<S, String> router) {
        edges.computeIfAbsent(from, k -> new ArrayList<>())
             .add(new GraphEdge<>(from, null, router, null));
        return this;
    }

    public StateGraph<S> setEntryPoint(String name) {
        if (!nodes.containsKey(name)) {
            throw new IllegalArgumentException("Entry point node not found: " + name);
        }
        this.entryPoint = name;
        return this;
    }

    /** 图可以重新访问任一节点的最大次数（防止无限循环） */
    public StateGraph<S> setMaxCycles(int maxCycles) {
        this.maxCycles = maxCycles;
        return this;
    }

    public GraphRunner<S> compile() {
        if (entryPoint == null) {
            throw new IllegalStateException("Entry point must be set before compiling");
        }
        return new GraphRunner<>(this);
    }

    // -- GraphRunner 的包级私有访问器 --

    Map<String, GraphNode<S>> getNodes() { return nodes; }
    Map<String, List<GraphEdge<S>>> getEdges() { return edges; }
    String getEntryPoint() { return entryPoint; }
    int getMaxCycles() { return maxCycles; }

    /**
     * 流畅图构建的 Builder 风格入口。
     */
    public static <S> StateGraph<S> create(Class<S> stateType) {
        return new StateGraph<>();
    }
}
