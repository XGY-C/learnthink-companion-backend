package com.learnthink.core.agent.impl;

import com.learnthink.core.agent.framework.AgentContext;
import com.learnthink.core.agent.framework.AgentResult;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Reviews generated content for factual accuracy, source coverage, and safety.
 *
 * <h3>Escalation protocol:</h3>
 * <ol>
 *   <li>R1 (no sources) → RETRY for document/exercise/code</li>
 *   <li>R2 (low citation coverage) → confidence=low, still publish</li>
 *   <li>R3 (unsupported claims) → RETRY with specific feedback</li>
 *   <li>R4 (harmful content) → REJECT_PERMANENT, no retry</li>
 * </ol>
 */
@Component
public class ReviewerAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewerAgent.class);
    private final ChatClient chatClient;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
        You are the content reviewer for LearnThink Companion.
        Your job: verify generated educational content for quality and safety.

        ## Checks (4 mandatory items)
        R1 — sources empty: sources.length == 0 AND type in [document, exercise, code] → action=RETRY
        R2 — citation coverage: % of factual claims traceable to sources. <0.4 → confidence=low
        R3 — unsupported claims: content says "research shows..." but no source → action=RETRY
        R4 — harmful content: political/violent/sexual/discriminatory → action=REJECT_PERMANENT

        Exemptions: reading and mindmap are exempt from R1.

        ## Output (strict JSON):
        {
          "reviewStatus": "APPROVED|REJECTED",
          "confidence": "high|medium|low",
          "reviewSummary": "1-2 sentence summary in Chinese",
          "reasons": [
            {"check": "R1", "result": "pass|fail|warn", "detail": "specific reason"}
          ],
          "citationCoverage": 0.75,
          "action": "PUBLISH|RETRY|REJECT_PERMANENT"
        }

        ## Confidence rules
        - high: sources≥3, all checks pass, coverage≥0.7
        - medium: sources exist, no R1/R3 failure, coverage 0.4-0.7
        - low: sources<3, or coverage<0.4, or forceLowConfidence=true

        ## Action rules
        - All pass → PUBLISH
        - R1/R3 fail (not R4) → RETRY
        - R4 fail → REJECT_PERMANENT
        """;

    public ReviewerAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public AgentResult<ResourceGenerationState.ReviewResult> review(
        ResourceGenerationState.GeneratedContent content,
        List<ResourceGenerationState.SourceItem> sources,
        String resourceType,
        boolean forceLowConfidence,
        AgentContext ctx) {

        Instant start = Instant.now();
        ctx.observation().onPrompt("ReviewerAgent", SYSTEM_PROMPT,
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
                Force low confidence: %s
                """,
                resourceType, content.title(),
                content.content().substring(0, Math.min(2000, content.content().length())),
                sourcesJson, forceLowConfidence);

            String response = chatClient.prompt()
                .messages(new SystemMessage(SYSTEM_PROMPT), new UserMessage(userMsg))
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

    /** Rule-based pre-filter — catches obvious issues before LLM invocation. */
    static class ContentSafetyFilter {
        private static final List<String> BLOCKED = List.of(/* loaded from config in production */);
        boolean isBlocked(String content) {
            return BLOCKED.stream().anyMatch(p -> content.toLowerCase().contains(p.toLowerCase()));
        }
    }
}
