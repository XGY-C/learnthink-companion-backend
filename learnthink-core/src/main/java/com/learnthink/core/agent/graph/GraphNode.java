package com.learnthink.core.agent.graph;

import java.util.function.Function;

/**
 * A processing node in the state graph. Each node transforms the state.
 */
public record GraphNode<S>(
    String name,
    Function<S, S> processor,
    String description
) {}
