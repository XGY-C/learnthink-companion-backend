package com.learnthink.core.agent.impl;

import com.learnthink.core.agent.runtime.AgentContext;
import com.learnthink.core.agent.runtime.AgentResult;
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
 * 通过查询重写流水线 + RAG 服务检索证据块
 *
 * <h3>v3.1 查询重写流水线（来自 03-RAG v3.0 §5A）：</h3>
 * <ol>
 *   <li>QueryDecomposer — 复杂查询拆分为子查询（LLM，约 200 tokens）</li>
 *   <li>QueryRewriter — 规范化学术术语（LLM，约 200 tokens）</li>
 *   <li>QueryExpander — 添加同义词/相关术语（规则 + LLM，约 150 tokens）</li>
 *   <li>MultiHopRouter — 检测比较/因果查询以进行多跳检索（基于规则，0 LLM）</li>
 * </ol>
 * 总预算：≤ 1K tokens，≤ 500ms
 */
@Component
public class EvidenceRetriever {

    private static final Logger log = LoggerFactory.getLogger(EvidenceRetriever.class);
    private final RagClient ragClient;
    private final ChatClient chatClient;

    public EvidenceRetriever(RagClient ragClient,
                             @Qualifier("chatChatClientBuilder") ChatClient.Builder chatClientBuilder) {
        this.ragClient = ragClient;
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 通过完整查询重写流水线为指定资源类型检索证据
     */
    public AgentResult<List<ResourceGenerationState.SourceItem>> retrieve(
        String courseId, String topic, String resourceType, AgentContext ctx) {

        log.info("=== EvidenceRetriever START === courseId={}, topic={}, type={}", courseId, topic, resourceType);
        Instant start = Instant.now();

        // 第一步：构建基础查询
        String baseQuery = buildBaseQuery(topic, resourceType);
        log.info("Base query: {}", baseQuery);
        ctx.observation().onPrompt("EvidenceRetriever", "base query: " + baseQuery,
            Map.of("courseId", courseId, "type", resourceType));

        // 第二步：查询重写流水线
        String rewrittenQuery = rewriteQuery(baseQuery, ctx);
        log.info("Rewritten query: {}", rewrittenQuery);

        // 第三步：多跳决策（基于规则）
        boolean isMultiHop = needsMultiHop(baseQuery);
        log.info("Multi-hop detection: {}", isMultiHop ? "YES" : "NO");
        int totalSources = 0;
        List<ResourceGenerationState.SourceItem> allSources = new ArrayList<>();

        try {
            if (isMultiHop) {
                log.info("Executing multi-hop retrieval");
                // 第一跳：检索基础概念
                RagClient.RagResponse hop1 = ragClient.retrieve(courseId, rewrittenQuery, topic, 200, 0.25, 1);
                if (hop1 != null && hop1.sources() != null) {
                    log.info("First hop retrieved {} sources", hop1.sources().size());
                    allSources.addAll(hop1.sources().stream().map(this::toSourceItem).toList());
                    // 从第一跳结果中提取关键实体用于第二跳查询
                    String secondQuery = buildMultiHopQuery(hop1, topic);
                    log.info("Second hop query: {}", secondQuery);
                    RagClient.RagResponse hop2 = ragClient.retrieve(courseId, secondQuery, topic, 200, 0.25, 1);
                    if (hop2 != null && hop2.sources() != null) {
                        log.info("Second hop retrieved {} sources", hop2.sources().size());
                        allSources.addAll(hop2.sources().stream().map(this::toSourceItem).toList());
                    }
                    // 去重
                    allSources = allSources.stream().distinct().toList();
                    totalSources = allSources.size();
                    ctx.observation().onDecision("EvidenceRetriever",
                        "MULTI_HOP", "2-hop retrieval: " + totalSources + " total sources");
                }
            } else {
                log.info("Executing single-hop retrieval");
                RagClient.RagResponse ragResp = ragClient.retrieve(courseId, rewrittenQuery, topic, 200, 0.25, 1);

                // 如果重写查询无结果，尝试使用基础查询重试
                if ((ragResp == null || ragResp.sources().isEmpty()) && !rewrittenQuery.equals(baseQuery)) {
                    log.warn("No results with rewritten query, retrying with base query: {}", baseQuery);
                    ragResp = ragClient.retrieve(courseId, baseQuery, topic, 200, 0.2, 1);
                }
                
                if (ragResp == null) {
                    log.warn("RAG response is null - KB not ready");
                    return AgentResult.error("KB_NOT_READY");
                }
                log.info("Retrieved {} sources from RAG", ragResp.sources().size());
                allSources = ragResp.sources().stream().map(this::toSourceItem).toList();
                totalSources = allSources.size();
            }

            long elapsed = java.time.Duration.between(start, Instant.now()).toMillis();
            log.info("Retrieval completed in {}ms with {} sources", elapsed, totalSources);
            ctx.observation().onResponse("EvidenceRetriever",
                "sources=" + totalSources + " (query: " + rewrittenQuery.substring(0, Math.min(80, rewrittenQuery.length())) + "...)",
                elapsed, AgentResult.TokenUsage.ZERO);

            boolean lowConfidence = totalSources < 3;
            if (lowConfidence) {
                log.warn("Low confidence: only {} sources found for {}", totalSources, resourceType);
                ctx.observation().onDecision("EvidenceRetriever",
                    "LOW_CONFIDENCE", "Only " + totalSources + " sources found for " + resourceType);
                ctx.put("forceLowConfidence", true);
            }

            log.info("EvidenceRetriever completed successfully");
            return AgentResult.of(allSources, AgentResult.TokenUsage.ZERO, elapsed,
                Map.of("agent", "EvidenceRetriever", "sourceCount", totalSources,
                       "multiHop", isMultiHop, "lowConfidence", lowConfidence));

        } catch (Exception e) {
            log.error("EvidenceRetriever failed for type={}: {}", resourceType, e.getMessage(), e);
            ctx.observation().onError("EvidenceRetriever", e);
            return AgentResult.error(e.getMessage());
        }
    }

    // ================================================================
    // 查询重写流水线（§5A from 03-RAG v3.0）
    // ================================================================

    /** 第0步：按资源类型的基础查询模板 */
    String buildBaseQuery(String topic, String resourceType) {
        return switch (resourceType) {
            case "doc"     -> topic + " 概念定义 核心原理 应用场景";
            case "quiz"    -> topic + " 习题 例题 练习 测试题";
            case "reading" -> topic + " 扩展阅读 前沿进展 相关领域";
            case "code"    -> topic + " 代码实现 算法 编程示例";
            case "mindmap" -> topic + " 知识结构 概念关系 思维导图";
            case "video"   -> topic + " 概念讲解 可视化 动画演示";
            default        -> topic;
        };
    }

    /** 第1-3步合并：通过 LLM 分解 + 重写 + 扩展 */
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

            log.info("[AI-RESPONSE][EvidenceRetriever] rewriteQuery: \"{}\" → \"{}\"", baseQuery, rewritten);
            if (rewritten != null && !rewritten.isBlank()) {
                ctx.observation().onDecision("EvidenceRetriever", "QUERY_REWRITTEN",
                    baseQuery + " → " + rewritten);
                return rewritten.trim();
            }
        } catch (Exception e) {
            log.warn("Query rewriting failed, using base query: {}", e.getMessage());
        }
        return baseQuery;
    }

    /** 第4步：基于规则的多跳检测（零LLM成本） */
    boolean needsMultiHop(String query) {
        Set<String> multiHopIndicators = Set.of(
            "区别", "对比", "比较", "哪个更好", "优缺点", "异同",
            "原因", "为什么", "原理", "推导", "怎么"
        );
        return multiHopIndicators.stream().anyMatch(query::contains);
    }

    /** 从第一跳结果构建第二跳查询 */
    String buildMultiHopQuery(RagClient.RagResponse hop1, String topic) {
        if (hop1 == null || hop1.sources() == null || hop1.sources().isEmpty()) return topic;
        // 从顶部来源中提取关键实体
        List<String> entities = hop1.sources().stream()
            .limit(3)
            .map(RagClient.SourceRef::chapterTitle)
            .filter(t -> t != null && !t.isBlank())
            .distinct()
            .toList();
        return topic + " " + String.join(" ", entities) + " 区别 对比 应用场景";
    }

    private ResourceGenerationState.SourceItem toSourceItem(RagClient.SourceRef s) {
        return new ResourceGenerationState.SourceItem(
            s.docId(), s.bookTitle(), s.bookType(), s.chapterIndex(), s.chapterTitle(),
            s.sourceType(), s.chunkId(), s.quote(), s.locator(), s.headingPath(),
            s.relevance());
    }

    // === RAG client abstraction ===

    @FunctionalInterface
    public interface RagClient {
        RagResponse retrieve(String courseId, String query, String topic, int k, double minRelevance, int minSources);
        record RagResponse(List<SourceRef> sources, String mode) {}
        record SourceRef(
            String docId,
            String bookTitle,
            String bookType,
            Integer chapterIndex,
            String chapterTitle,
            String sourceType,
            String chunkId,
            String quote,
            String locator,
            String headingPath,
            double relevance
        ) {}
    }
}
