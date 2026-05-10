package com.learnthink.core.agent.graph;

/**
 * Thrown when graph execution encounters a fatal error.
 */
public class GraphException extends RuntimeException {
    private final String nodeName;

    public GraphException(String nodeName, String message, Throwable cause) {
        super("Graph error at node '" + nodeName + "': " + message, cause);
        this.nodeName = nodeName;
    }

    public String getNodeName() { return nodeName; }
}
