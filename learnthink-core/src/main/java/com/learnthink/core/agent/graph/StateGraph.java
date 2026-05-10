package com.learnthink.core.agent.graph;

import java.util.*;
import java.util.function.Function;

/**
 * Generic state graph for multi-agent orchestration.
 * Supports conditional routing, cycles (feedback loops), and observability.
 *
 * @param <S> the state type that flows through the graph
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

    /** Unconditional transition: from → to always */
    public StateGraph<S> addEdge(String from, String to) {
        edges.computeIfAbsent(from, k -> new ArrayList<>())
             .add(new GraphEdge<>(from, to, null, null));
        return this;
    }

    /**
     * Conditional transition: after 'from' executes, the router function decides
     * which node to go to next based on state.
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

    /** Maximum times the graph can revisit any single node (prevents infinite loops) */
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

    // -- Package-private accessors for GraphRunner --

    Map<String, GraphNode<S>> getNodes() { return nodes; }
    Map<String, List<GraphEdge<S>>> getEdges() { return edges; }
    String getEntryPoint() { return entryPoint; }
    int getMaxCycles() { return maxCycles; }

    /**
     * Builder-style entry point for fluent graph construction.
     */
    public static <S> StateGraph<S> create(Class<S> stateType) {
        return new StateGraph<>();
    }
}
