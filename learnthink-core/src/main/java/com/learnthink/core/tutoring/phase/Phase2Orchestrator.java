package com.learnthink.core.tutoring.phase;

import com.learnthink.core.agent.impl.EvidenceRetriever.RagClient;
import com.learnthink.core.config.TutoringConfig;
import com.learnthink.core.tutoring.domain.ResolvedResources;
import com.learnthink.core.tutoring.domain.ResourceRequirement;
import com.learnthink.core.tutoring.domain.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

@Component
public class Phase2Orchestrator {
    private static final Logger log = LoggerFactory.getLogger(Phase2Orchestrator.class);

    private final RagClient ragClient;
    private final TutoringConfig config;
    private final ExecutorService executor;

    public Phase2Orchestrator(RagClient ragClient, TutoringConfig config) {
        this.ragClient = ragClient;
        this.config = config;
        this.executor = Executors.newFixedThreadPool(config.getPhase2().getThreadPoolSize());
    }

    public ResolvedResources execute(List<ResourceRequirement> requirements, String courseId) {
        if (requirements == null || requirements.isEmpty()) {
            return new ResolvedResources(Map.of());
        }

        Map<String, CompletableFuture<List<RetrievedChunk>>> futures = new HashMap<>();

        for (ResourceRequirement req : requirements) {
            futures.put(req.id(), CompletableFuture.supplyAsync(
                () -> retrieveSingle(req, courseId), executor));
        }

        Map<String, List<RetrievedChunk>> result = new HashMap<>();
        for (var entry : futures.entrySet()) {
            try {
                List<RetrievedChunk> chunks = entry.getValue()
                    .get(config.getRag().getPerQueryTimeoutMs(), TimeUnit.MILLISECONDS);
                result.put(entry.getKey(), chunks);
            } catch (TimeoutException e) {
                log.warn("RAG query timeout for requirement {}", entry.getKey());
                result.put(entry.getKey(), List.of());
            } catch (Exception e) {
                log.warn("RAG query failed for requirement {}: {}", entry.getKey(), e.getMessage());
                result.put(entry.getKey(), List.of());
            }
        }

        return new ResolvedResources(result);
    }

    /** 兼容旧调用 */
    public ResolvedResources execute(List<ResourceRequirement> requirements) {
        return execute(requirements, null);
    }

    private List<RetrievedChunk> retrieveSingle(ResourceRequirement req, String courseId) {
        String resolvedCourseId = (courseId != null && !courseId.isBlank()) ? courseId : "default";
        int topK = "core".equals(req.priority())
            ? config.getRag().getCrossEncoderTopNCore()
            : config.getRag().getCrossEncoderTopNSupplement();

        try {
            RagClient.RagResponse resp = ragClient.retrieve(resolvedCourseId, req.query(), "", topK, 0.0, 1);
            if (resp == null || resp.sources() == null) {
                return List.of();
            }
            return resp.sources().stream()
                .map(s -> new RetrievedChunk(
                    s.chunkId() != null ? s.chunkId() : "",
                    s.quote() != null ? s.quote() : "",
                    s.bookTitle() != null ? s.bookTitle() : "",
                    s.chapterTitle() != null ? s.chapterTitle() : "",
                    s.relevance()))
                .toList();
        } catch (Exception e) {
            log.warn("RAG retrieve failed for requirement {}: {}", req.id(), e.getMessage());
            return List.of();
        }
    }
}
