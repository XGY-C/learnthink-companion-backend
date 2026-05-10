package com.learnthink.core.agent.impl;

import com.learnthink.core.agent.framework.AgentContext;
import com.learnthink.core.agent.framework.AgentResult;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Retrieves evidence chunks from the Python RAG service (or JSONL fallback).
 * Each resource type gets its own targeted retrieval query.
 */
@Component
public class RetrieverAgent {

    private static final Logger log = LoggerFactory.getLogger(RetrieverAgent.class);
    private final RagClient ragClient;

    public RetrieverAgent(RagClient ragClient) {
        this.ragClient = ragClient;
    }

    /**
     * Retrieve evidence for a specific resource type.
     * @return evidence pack, or empty list if retrieval failed (non-fatal for reading/mindmap)
     */
    public AgentResult<List<ResourceGenerationState.SourceItem>> retrieve(
        String courseId, String topic, String resourceType, AgentContext ctx) {

        Instant start = Instant.now();
        String query = buildQuery(topic, resourceType);

        ctx.observation().onPrompt("RetrieverAgent", query,
            Map.of("courseId", courseId, "type", resourceType));

        try {
            RagClient.RagResponse ragResp = ragClient.retrieve(courseId, query, topic, 8, 0.4, 2);

            if (ragResp == null) {
                return AgentResult.error("KB_NOT_READY");
            }

            List<ResourceGenerationState.SourceItem> sources = ragResp.sources().stream()
                .map(s -> new ResourceGenerationState.SourceItem(
                    s.docId(), s.title(), s.chunkId(),
                    s.quote(), s.locator(), s.relevance()))
                .toList();

            long elapsed = java.time.Duration.between(start, Instant.now()).toMillis();
            ctx.observation().onResponse("RetrieverAgent",
                "sources=" + sources.size(), elapsed, AgentResult.TokenUsage.ZERO);

            boolean lowConfidence = sources.size() < 3;
            if (lowConfidence) {
                ctx.observation().onDecision("RetrieverAgent",
                    "LOW_CONFIDENCE", "Only " + sources.size() + " sources found for " + resourceType);
                ctx.put("forceLowConfidence", true);
            }

            return AgentResult.of(sources, AgentResult.TokenUsage.ZERO, elapsed,
                Map.of("agent", "RetrieverAgent", "sourceCount", sources.size(),
                       "mode", ragResp.mode(), "lowConfidence", lowConfidence));

        } catch (Exception e) {
            log.error("RetrieverAgent failed for type={}: {}", resourceType, e.getMessage());
            ctx.observation().onError("RetrieverAgent", e);
            return AgentResult.error(e.getMessage());
        }
    }

    /**
     * Build a type-specific search query — different resource types need different evidence.
     */
    public String buildQuery(String topic, String resourceType) {
        return switch (resourceType) {
            case "document" -> topic + " 概念定义 核心原理 应用场景";
            case "exercise" -> topic + " 习题 例题 练习 测试题";
            case "reading"  -> topic + " 扩展阅读 前沿进展 相关领域";
            case "code"     -> topic + " 代码实现 算法 编程示例";
            case "mindmap"  -> topic + " 知识结构 概念关系 思维导图";
            default         -> topic;
        };
    }

    // === RAG client abstraction (decouples from Python service details) ===

    @FunctionalInterface
    public interface RagClient {
        RagResponse retrieve(String courseId, String query, String topic, int k, double minRelevance, int minSources);

        record RagResponse(List<SourceRef> sources, String mode) {}
        record SourceRef(String docId, String title, String chunkId, String quote, String locator, double relevance) {}
    }
}
