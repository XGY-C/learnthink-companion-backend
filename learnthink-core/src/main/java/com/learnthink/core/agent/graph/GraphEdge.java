package com.learnthink.core.agent.graph;

import java.util.function.Function;

/**
 * 状态图中的边。
 * <ul>
 * <li>如果 {@code to} 非空：无条件转移。</li>
 * <li>如果 {@code router} 非空：条件转移 — router 函数检查状态并返回下一个节点的名称。</li>
 * </ul>
 */
public record GraphEdge<S>(
    String from,
    String to,
    Function<S, String> router,
    String description
) {
    public boolean isConditional() {
        return router != null;
    }
}
