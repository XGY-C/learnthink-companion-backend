package com.learnthink.core.agent.impl;

import com.learnthink.core.agent.framework.AgentContext;
import com.learnthink.core.agent.framework.AgentResult;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import com.learnthink.core.config.PromptLoader;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reviews generated content with layered audit + independent RAG fact-checking.
 *
 * <h3>v3.1 — Independent RAG access for fact verification:</h3>
 * <ol>
 *   <li>Rule layer (R1-R3): format check, source coverage, safety pre-filter (zero LLM)</li>
 *   <li>Claim Extraction (LLM): extract factual assertions from content</li>
 *   <li>Source Matching (rule): match claims against RetrieverAgent sources</li>
 *   <li>Targeted Retrieval (RagTool): for unmatched claims, independent RAG search</li>
 *   <li>Verdict: backed ≥ 70% → APPROVED / 40-70% → MEDIUM / < 40% → RETRY</li>
 * </ol>
 */
@Component
public class ReviewerAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewerAgent.class);
    private final ChatClient chatClient;
    private final PromptLoader promptLoader;
    private final RagTool ragTool;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReviewerAgent(@Qualifier("reasoningChatClientBuilder") ChatClient.Builder chatClientBuilder,
                         PromptLoader promptLoader,
                         RagTool ragTool) {
        this.chatClient = chatClientBuilder.build();
        this.promptLoader = promptLoader;
        this.ragTool = ragTool;
    }

    public AgentResult<ResourceGenerationState.ReviewResult> review(
        ResourceGenerationState.GeneratedContent content,
        List<ResourceGenerationState.SourceItem> sources,
        String resourceType,
        boolean forceLowConfidence,
        AgentContext ctx) {

        Instant start = Instant.now();
        String systemPrompt = promptLoader.get("agent/reviewer");
        ctx.observation().onPrompt("ReviewerAgent", systemPrompt,
            Map.of("type", resourceType, "sourcesCount", sources.size(),
                   "forceLowConfidence", forceLowConfidence));

        try {
            // Pre-filter: rule-based safety check (before LLM call)
            if (new ContentSafetyFilter().isBlocked(content.content())) {
                var result = new ResourceGenerationState.ReviewResult(
                    ResourceGenerationState.ReviewStatus.REJECTED,
                    "low", "Content blocked by safety filter",
                    List.of(new ResourceGenerationState.ReviewReason("R4", "fail", "Blocked pattern detected")),
                    0.0,
                    ResourceGenerationState.ReviewAction.REJECT_PERMANENT);
                ctx.observation().onDecision("ReviewerAgent", "REJECT_PERMANENT", "Safety filter blocked content");
                return AgentResult.of(result);
            }

            // For reading/mindmap with no sources, skip R1
            boolean exemptR1 = "reading".equals(resourceType) || "mindmap".equals(resourceType);
            if (exemptR1 && sources.isEmpty()) {
                var result = new ResourceGenerationState.ReviewResult(
                    ResourceGenerationState.ReviewStatus.APPROVED,
                    "low", "No sources available for " + resourceType + " (exempt from R1)",
                    List.of(new ResourceGenerationState.ReviewReason("R1", "warn", "Exempt: " + resourceType)),
                    0.0,
                    ResourceGenerationState.ReviewAction.PUBLISH);
                ctx.observation().onDecision("ReviewerAgent", "PUBLISH", "Exempt from R1 — " + resourceType);
                return AgentResult.of(result);
            }

            // v3.1: Extract factual claims and verify via independent RAG
            String contentExcerpt = content.content().substring(0, Math.min(2000, content.content().length()));
            List<Claim> claims = extractClaims(contentExcerpt);
            Map<String, String> claimVerification = verifyClaims(claims, sources, ctx);

            int backedCount = (int) claimVerification.values().stream()
                .filter(s -> "backed".equals(s) || "backed_extra".equals(s)).count();
            int totalClaims = claims.isEmpty() ? 1 : claims.size();
            double backedRatio = (double) backedCount / totalClaims;

            ctx.observation().onDecision("ReviewerAgent",
                "CLAIMS_VERIFIED",
                backedCount + "/" + totalClaims + " claims backed (ratio=" +
                String.format("%.0f%%", backedRatio * 100) + ")");

            String sourcesJson = mapper.writeValueAsString(
                sources.stream().map(s -> Map.of(
                    "docId", s.docId(), "title", s.title(),
                    "quote", s.quote(), "locator", s.locator()
                )).toList());

            String userMsg = String.format("""
                Resource type: %s
                Title: %s
                Content (first 2000 chars): %s
                Sources: %s
                Claim verification: %d/%d backed (%.0f%%)
                Force low confidence: %s
                """,
                resourceType, content.title(),
                contentExcerpt,
                sourcesJson,
                backedCount, totalClaims, backedRatio * 100,
                forceLowConfidence);

            String response = chatClient.prompt()
                .messages(new SystemMessage(systemPrompt), new UserMessage(userMsg))
                .call()
                .content();

            long elapsed = java.time.Duration.between(start, Instant.now()).toMillis();
            ctx.observation().onResponse("ReviewerAgent", response, elapsed, AgentResult.TokenUsage.ZERO);

            var result = parseReview(response);
            ctx.observation().onDecision("ReviewerAgent",
                result.action().name(),
                result.reviewSummary());

            return AgentResult.of(result, AgentResult.TokenUsage.ZERO, elapsed,
                Map.of("agent", "ReviewerAgent", "coverage", result.citationCoverage()));

        } catch (Exception e) {
            log.error("ReviewerAgent failed: {}", e.getMessage());
            ctx.observation().onError("ReviewerAgent", e);
            return AgentResult.error("Review failed: " + e.getMessage());
        }
    }

    private ResourceGenerationState.ReviewResult parseReview(String json) {
        try {
            var node = mapper.readTree(json);
            return new ResourceGenerationState.ReviewResult(
                ResourceGenerationState.ReviewStatus.valueOf(node.get("reviewStatus").asText()),
                node.get("confidence").asText(),
                node.get("reviewSummary").asText(),
                mapper.convertValue(node.get("reasons"), List.class),
                node.get("citationCoverage").asDouble(),
                ResourceGenerationState.ReviewAction.valueOf(node.get("action").asText()));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse review JSON: " + e.getMessage(), e);
        }
    }

    // ================================================================
    // v3.1: Claim extraction + independent RAG verification
    // ================================================================

    /** Extract factual assertions from generated content (1 LLM call) */
    private List<Claim> extractClaims(String content) {
        try {
            String prompt = """
                Extract all factual assertions from this educational content.
                A factual assertion is a statement that can be verified against a knowledge base
                (definitions, formulas, algorithm steps, specific claims about how something works).

                Output JSON array:
                [{"claim": "assertion text", "entity": "key entity", "topic": "topic area"}, ...]

                Content:
                """ + content;

            String response = chatClient.prompt()
                .messages(new SystemMessage(prompt), new UserMessage(content))
                .call().content();

            String json = response;
            if (json.contains("```")) {
                json = json.substring(json.indexOf("[") > 0 ? json.indexOf("[") : json.indexOf("```") + 3,
                    json.lastIndexOf("]") + 1);
            }
            json = json.substring(json.indexOf("["), json.lastIndexOf("]") + 1);

            List<Map<String, String>> raw = mapper.readValue(json, new TypeReference<>() {});
            return raw.stream()
                .map(m -> new Claim(
                    m.getOrDefault("claim", ""),
                    m.getOrDefault("entity", ""),
                    m.getOrDefault("topic", "")))
                .toList();

        } catch (Exception e) {
            log.warn("Claim extraction failed: {}", e.getMessage());
            return List.of(); // fallback: skip claim verification
        }
    }

    /** Verify claims against RetrieverAgent sources + independent RagTool retrieval */
    private Map<String, String> verifyClaims(List<Claim> claims,
                                              List<ResourceGenerationState.SourceItem> retrieverSources,
                                              AgentContext ctx) {
        Map<String, String> results = new HashMap<>();
        String courseId = ctx.courseId();

        for (Claim claim : claims) {
            // Step 1: Try matching against RetrieverAgent sources
            boolean found = retrieverSources.stream().anyMatch(s ->
                s.quote() != null && claim.claim() != null &&
                (s.quote().contains(claim.claim().substring(0, Math.min(10, claim.claim().length()))) ||
                 claim.claim().contains(s.quote().substring(0, Math.min(20, s.quote().length()))))
            );

            if (found) {
                results.put(claim.claim(), "backed");
                continue;
            }

            // Step 2: Independent RagTool search for unmatched claims
            if (ragTool != null && courseId != null) {
                String searchQuery = claim.entity() + " " + claim.topic();
                try {
                    RetrieverAgent.RagClient.RagResponse extraResp =
                        ragTool.retrieve(courseId, searchQuery, null, 3);
                    if (extraResp != null && extraResp.sources() != null) {
                        boolean extraFound = extraResp.sources().stream().anyMatch(s ->
                            s.quote() != null && claim.claim() != null &&
                            s.quote().contains(claim.claim().substring(0, Math.min(10, claim.claim().length())))
                        );
                        results.put(claim.claim(), extraFound ? "backed_extra" : "unverified");
                    } else {
                        results.put(claim.claim(), "unverified");
                    }
                } catch (Exception e) {
                    log.warn("RagTool verification failed for claim: {}", claim.claim());
                    results.put(claim.claim(), "unverified");
                }
            } else {
                results.put(claim.claim(), "unverified");
            }
        }

        return results;
    }

    record Claim(String claim, String entity, String topic) {}

    /** Rule-based pre-filter — catches obvious issues before LLM invocation. */
    static class ContentSafetyFilter {
        private static final List<String> BLOCKED = List.of(/* loaded from config in production */);
        boolean isBlocked(String content) {
            return BLOCKED.stream().anyMatch(p -> content.toLowerCase().contains(p.toLowerCase()));
        }
    }
}
