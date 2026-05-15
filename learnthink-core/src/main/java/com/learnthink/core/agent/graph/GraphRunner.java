package com.learnthink.core.agent.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

/**
 * Compiled executor for a {@link StateGraph}. Supports:
 * <ul>
 *   <li>Sequential node execution with state transformation</li>
 *   <li>Conditional routing — each node can decide where to go next via router functions</li>
 *   <li>Cycle support — nodes can be revisited up to {@code maxCycles} times (enables feedback loops)</li>
 *   <li>Observability — pluggable observers for tracing each step</li>
 *   <li>Cancellation — cancellable via external signal</li>
 * </ul>
 */
public class GraphRunner<S> {

    private static final Logger log = LoggerFactory.getLogger(GraphRunner.class);

    private final StateGraph<S> graph;
    private final List<GraphObserver<S>> observers = new ArrayList<>();
    private Duration nodeTimeout = Duration.ofMinutes(5);
    private volatile boolean cancelled = false;

    GraphRunner(StateGraph<S> graph) {
        this.graph = graph;
    }

    public GraphRunner<S> withObserver(GraphObserver<S> observer) {
        this.observers.add(observer);
        return this;
    }

    public GraphRunner<S> withNodeTimeout(Duration timeout) {
        this.nodeTimeout = timeout;
        return this;
    }

    /** Execute the graph, returning the final state */
    public S execute(S initialState) {
        return execute(initialState, null);
    }

    /** Execute the graph on a given executor, returning the final state */
    public S execute(S initialState, ExecutorService executor) {
        Instant start = Instant.now();
        S state = initialState;
        String currentNode = graph.getEntryPoint();
        Map<String, Integer> visitCount = new HashMap<>();
        List<String> path = new ArrayList<>();

        log.info("Graph execution started. Entry: {}", currentNode);
        log.debug("Initial state type: {}", initialState.getClass().getSimpleName());

        while (currentNode != null) {
            if (cancelled) {
                log.info("Graph execution cancelled at node: {}", currentNode);
                notifyError(currentNode, state,
                    new GraphException(currentNode, "Execution cancelled", null));
                return state;
            }

            // Cycle protection
            int visits = visitCount.merge(currentNode, 1, Integer::sum);
            if (visits > graph.getMaxCycles()) {
                log.warn("Node '{}' visited {} times, exceeding max cycles. Breaking.", currentNode, visits);
                notifyError(currentNode, state,
                    new GraphException(currentNode, "Max cycles exceeded (" + visits + ")", null));
                break;
            }

            GraphNode<S> node = graph.getNodes().get(currentNode);
            if (node == null) {
                throw new GraphException(currentNode, "Node not found in graph", null);
            }

            log.debug("Executing node: {} (visit #{})", currentNode, visits);
            path.add(currentNode);
            notifyNodeStart(currentNode, state, visits);
            log.info("Entering node: {} (visit #{})", currentNode, visits);

            try {
                Instant nodeStart = Instant.now();
                state = executeWithTimeout(node, state, executor);
                long elapsed = Duration.between(nodeStart, Instant.now()).toMillis();
                notifyNodeComplete(currentNode, state, elapsed);

                log.info("Node '{}' completed in {}ms", currentNode, elapsed);
            } catch (Exception e) {
                log.error("Node '{}' failed: {}", currentNode, e.getMessage());
                notifyError(currentNode, state, e);
                throw new GraphException(currentNode, e.getMessage(), e);
            }

            // Determine next node
            String nextNode = resolveNextNode(currentNode, state);
            if (nextNode != null) {
                notifyRouting(currentNode, nextNode, state);
            }
            currentNode = nextNode;
        }

        long totalMs = Duration.between(start, Instant.now()).toMillis();
        log.info("Graph execution completed in {}ms. Path: {}", totalMs, path);
        notifyGraphComplete(state, totalMs);
        return state;
    }

    /** Cancel graph execution at the next node boundary */
    public void cancel() {
        this.cancelled = true;
    }

    // -- Private helpers --

    private S executeWithTimeout(GraphNode<S> node, S state, ExecutorService executor) throws Exception {
        if (executor != null) {
            return executor.submit((Callable<S>) () -> node.processor().apply(state))
                .get(nodeTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        return node.processor().apply(state);
    }

    private String resolveNextNode(String currentNode, S state) {
        List<GraphEdge<S>> outEdges = graph.getEdges().getOrDefault(currentNode, List.of());

        for (GraphEdge<S> edge : outEdges) {
            if (edge.isConditional()) {
                // Use the router function to determine next node from state
                String next = edge.router().apply(state);
                if (next != null && !next.isEmpty()) {
                    return next;
                }
            } else {
                // Unconditional transition
                return edge.to();
            }
        }
        return null; // Terminal node
    }

    private void notifyNodeStart(String name, S state, int visitCount) {
        for (GraphObserver<S> o : observers) {
            try { o.onNodeStart(name, state, visitCount); } catch (Exception ignored) {}
        }
    }

    private void notifyNodeComplete(String name, S state, long elapsedMs) {
        for (GraphObserver<S> o : observers) {
            try { o.onNodeComplete(name, state, elapsedMs); } catch (Exception ignored) {}
        }
    }

    private void notifyRouting(String from, String to, S state) {
        for (GraphObserver<S> o : observers) {
            try { o.onRouting(from, to, state); } catch (Exception ignored) {}
        }
    }

    private void notifyGraphComplete(S state, long totalMs) {
        for (GraphObserver<S> o : observers) {
            try { o.onGraphComplete(state, totalMs); } catch (Exception ignored) {}
        }
    }

    private void notifyError(String node, S state, Throwable error) {
        for (GraphObserver<S> o : observers) {
            try { o.onGraphError(node, state, error); } catch (Exception ignored) {}
        }
    }
}
