package com.learnthink.core.agent.graph;

import java.util.function.Function;

/**
 * An edge in the state graph.
 * <ul>
 * <li>If {@code to} is non-null: unconditional transition.</li>
 * <li>If {@code router} is non-null: conditional transition — the router function
 *     inspects the state and returns the name of the next node.</li>
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
