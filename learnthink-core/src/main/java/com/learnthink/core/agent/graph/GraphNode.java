package com.learnthink.core.agent.graph;

import java.util.function.Function;

/**
 * 状态图中的处理节点。每个节点对状态进行转换。
 */
public record GraphNode<S>(
    String name,
    Function<S, S> processor,
    String description
) {}
