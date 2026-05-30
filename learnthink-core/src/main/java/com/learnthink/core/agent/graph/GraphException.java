package com.learnthink.core.agent.graph;

/**
 * 当图执行遇到致命错误时抛出。
 */
public class GraphException extends RuntimeException {
    private final String nodeName;

    public GraphException(String nodeName, String message, Throwable cause) {
        super("Graph error at node '" + nodeName + "': " + message, cause);
        this.nodeName = nodeName;
    }

    public String getNodeName() { return nodeName; }
}
