package com.learnthink.core.agent.impl;

import com.learnthink.core.agent.framework.AgentContext;
import com.learnthink.core.agent.framework.AgentResult;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Retrieves evidence chunks via the query rewriting pipeline + RAG service.
 *
 * <h3>v3.1 Query Rewriting Pipeline (from 03-RAG v3.0 §5A):</h3>
 * <ol>
 *   <li>QueryDecomposer — complex queries → sub-queries (LLM, ~200 tokens)</li>
 *   <li>QueryRewriter — normalize academic terminology (LLM, ~200 tokens)</li>
 *   <li>QueryExpander — add synonyms/related terms (rule + LLM, ~150 tokens)</li>
 *   <li>MultiHopRouter — detect comparison/causal queries for multi-hop (rule-based, 0 LLM)</li>
 * </ol>
 * Total budget: ≤ 1K tokens, ≤ 500ms.
 */
@Component
public class RetrieverAgent {

    private static final Logger log = LoggerFactory.getLogger(RetrieverAgent.class);
    private final RagClient ragClient;
    private final ChatClient chatClient;

    public RetrieverAgent(RagClient ragClient,
                          @Qualifier("chatChatClientBuilder") ChatClient.Builder chatClientBuilder) {
        this.ragClient = ragClient;
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Retrieve evidence for a specific resource type through the full query rewriting pipeline.
     */
    public AgentResult<List<ResourceGenerationState.SourceItem>> retrieve(
        String courseId, String topic, String resourceType, AgentContext ctx) {

        Instant start = Instant.now();

        // Step 1: Build base query
        String baseQuery = buildBaseQuery(topic, resourceType);
        ctx.observation().onPrompt("RetrieverAgent", "base query: " + baseQuery,
            Map.of("courseId", courseId, "type", resourceType));

        // Step 2: Query rewriting pipeline
        String rewrittenQuery = rewriteQuery(baseQuery, ctx);

        // Step 3: Multi-hop decision (rule-based)
        boolean isMultiHop = needsMultiHop(baseQuery);
        int totalSources = 0;
        List<ResourceGenerationState.SourceItem> allSources = new ArrayList<>();

        try {
            if (isMultiHop) {
                // First hop: retrieve base concepts
                RagClient.RagResponse hop1 = ragClient.retrieve(courseId, rewrittenQuery, topic, 8, 0.4, 2);
                if (hop1 != null && hop1.sources() != null) {
                    allSources.addAll(hop1.sources().stream().map(this::toSourceItem).toList());
                    // Extract key entities from first hop for second query
                    String secondQuery = buildMultiHopQuery(hop1, topic);
                    RagClient.RagResponse hop2 = ragClient.retrieve(courseId, secondQuery, topic, 8, 0.4, 2);
                    if (hop2 != null && hop2.sources() != null) {
                        allSources.addAll(hop2.sources().stream().map(this::toSourceItem).toList());
                    }
                    // Deduplicate
                    allSources = allSources.stream().distinct().toList();
                    totalSources = allSources.size();
                    ctx.observation().onDecision("RetrieverAgent",
                        "MULTI_HOP", "2-hop retrieval: " + totalSources + " total sources");
                }
            } else {
                RagClient.RagResponse ragResp = ragClient.retrieve(courseId, rewrittenQuery, topic, 8, 0.4, 2);
                if (ragResp == null) {
                    return AgentResult.error("KB_NOT_READY");
                }
                allSources = ragResp.sources().stream().map(this::toSourceItem).toList();
                totalSources = allSources.size();
            }

            long elapsed = java.time.Duration.between(start, Instant.now()).toMillis();
            ctx.observation().onResponse("RetrieverAgent",
                "sources=" + totalSources + " (query: " + rewrittenQuery.substring(0, Math.min(80, rewrittenQuery.length())) + "...)",
                elapsed, AgentResult.TokenUsage.ZERO);

            boolean lowConfidence = totalSources < 3;
            if (lowConfidence) {
                ctx.observation().onDecision("RetrieverAgent",
                    "LOW_CONFIDENCE", "Only " + totalSources + " sources found for " + resourceType);
                ctx.put("forceLowConfidence", true);
            }

            return AgentResult.of(allSources, AgentResult.TokenUsage.ZERO, elapsed,
                Map.of("agent", "RetrieverAgent", "sourceCount", totalSources,
                       "multiHop", isMultiHop, "lowConfidence", lowConfidence));

        } catch (Exception e) {
            log.error("RetrieverAgent failed for type={}: {}", resourceType, e.getMessage());
            ctx.observation().onError("RetrieverAgent", e);
            return AgentResult.error(e.getMessage());
        }
    }

    // ================================================================
    // Query rewriting pipeline (§5A from 03-RAG v3.0)
    // ================================================================

    /** Step 0: Base type-specific query template */
    String buildBaseQuery(String topic, String resourceType) {
        return switch (resourceType) {
            case "document" -> topic + " 概念定义 核心原理 应用场景";
            case "exercise" -> topic + " 习题 例题 练习 测试题";
            case "reading"  -> topic + " 扩展阅读 前沿进展 相关领域";
            case "code"     -> topic + " 代码实现 算法 编程示例";
            case "mindmap"  -> topic + " 知识结构 概念关系 思维导图";
            default         -> topic;
        };
    }

    /** Steps 1-3 combined: decompose + rewrite + expand via LLM */
    String rewriteQuery(String baseQuery, AgentContext ctx) {
        try {
            String prompt = """
                Rewrite this student question into an optimized academic search query.
                Steps:
                1. If complex (multiple concepts / comparison), decompose into key aspects
                2. Normalize informal terms to academic terminology
                3. Add relevant synonyms and related concepts

                Input: %s
                Output: optimized search query (single line, Chinese + key English terms)
                """.formatted(baseQuery);

            String rewritten = chatClient.prompt()
                .messages(new SystemMessage(prompt), new UserMessage(baseQuery))
                .call().content();

            if (rewritten != null && !rewritten.isBlank()) {
                ctx.observation().onDecision("RetrieverAgent", "QUERY_REWRITTEN",
                    baseQuery + " → " + rewritten);
                return rewritten.trim();
            }
        } catch (Exception e) {
            log.warn("Query rewriting failed, using base query: {}", e.getMessage());
        }
        return baseQuery;
    }

    /** Step 4: Rule-based multi-hop detection (zero LLM cost) */
    boolean needsMultiHop(String query) {
        Set<String> multiHopIndicators = Set.of(
            "区别", "对比", "比较", "哪个更好", "优缺点", "异同",
            "原因", "为什么", "原理", "推导", "怎么"
        );
        return multiHopIndicators.stream().anyMatch(query::contains);
    }

    /** Build second-hop query from first-hop results */
    String buildMultiHopQuery(RagClient.RagResponse hop1, String topic) {
        if (hop1 == null || hop1.sources() == null || hop1.sources().isEmpty()) return topic;
        // Extract key entities from top sources
        List<String> entities = hop1.sources().stream()
            .limit(3)
            .map(RagClient.SourceRef::title)
            .filter(t -> t != null && !t.isBlank())
            .distinct()
            .toList();
        return topic + " " + String.join(" ", entities) + " 区别 对比 应用场景";
    }

    private ResourceGenerationState.SourceItem toSourceItem(RagClient.SourceRef s) {
        return new ResourceGenerationState.SourceItem(
            s.docId(), s.title(), s.chunkId(), s.quote(), s.locator(), s.relevance());
    }

    // === RAG client abstraction ===

    @FunctionalInterface
    public interface RagClient {
        RagResponse retrieve(String courseId, String query, String topic, int k, double minRelevance, int minSources);
        record RagResponse(List<SourceRef> sources, String mode) {}
        record SourceRef(String docId, String title, String chunkId, String quote, String locator, double relevance) {}
    }
}
