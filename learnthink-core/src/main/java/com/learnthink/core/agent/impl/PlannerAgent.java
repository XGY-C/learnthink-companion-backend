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
import java.util.stream.Collectors;

/**
 * Plans the resource composition, difficulty, and unified outline.
 * Supports replanning when the previous plan resulted in too many rejections.
 */
@Component
public class PlannerAgent {

    private static final Logger log = LoggerFactory.getLogger(PlannerAgent.class);
    private final ChatClient chatClient;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
        You are the learning planner for LearnThink Companion.
        Your job: plan a personalized set of learning resources for a student.

        ## Input
        - Student profile summary: {profile_summary}
        - Available evidence sources: {merged_sources_summary}
        - Target topic: {topic}
        - Requested resource types: {resource_types}
        - Previous plan feedback (if replanning): {plan_feedback}

        ## Output (strict JSON)
        {
          "topicOutline": "## 1. Concept\\n## 2. Core Principles\\n## 3. Examples\\n## 4. Common Pitfalls\\n## 5. Summary",
          "items": [
            {
              "type": "document",
              "title": "Resource title",
              "difficulty": "easy|medium|hard",
              "estimatedMinutes": 15,
              "keyPoints": ["point1", "point2"],
              "personalizationNote": "Because you prefer X, this resource emphasizes Y"
            }
          ],
          "pushReason": ["Reason 1 (≤15 chars)", "Reason 2", "Reason 3"],
          "queries": ["search query 1", "search query 2"]
        }

        ## Rules
        - Create exactly one item per requested resource type
        - difficulty: match to student's weak areas (hard for weak topics, medium for review)
        - estimatedMinutes: 10-30 minutes based on student's daily time
        - personalizationNote: MUST reference specific profile details
        - pushReason: 2-3 reasons, each ≤15 Chinese characters
        - If replanning with feedback, address the specific issues mentioned
        - Plan the topicOutline FIRST, then derive items from it
        """;

    public PlannerAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * @param feedback previous plan feedback if replanning, null for first attempt
     */
    public AgentResult<ResourceGenerationState.ResourcePlan> plan(
        ResourceGenerationState.ProfileSummary profile,
        List<ResourceGenerationState.SourceItem> mergedSources,
        String topic,
        List<String> resourceTypes,
        String feedback,
        AgentContext ctx) {

        Instant start = Instant.now();
        String contextInfo = feedback != null
            ? "REPLANNING with feedback: " + feedback
            : "Initial planning";

        ctx.observation().onPrompt("PlannerAgent", SYSTEM_PROMPT,
            Map.of("topic", topic, "types", resourceTypes, "replanning", feedback != null));

        try {
            String sourcesSummary = buildSourcesSummary(mergedSources);
            String profileJson = mapper.writeValueAsString(profile);

            String prompt = String.format("""
                {profile_summary}: %s
                {merged_sources_summary}: %s
                {topic}: %s
                {resource_types}: %s
                {plan_feedback}: %s
                Context: %s
                """,
                profileJson, sourcesSummary, topic,
                String.join(", ", resourceTypes),
                feedback != null ? feedback : "N/A (initial plan)",
                contextInfo);

            String response = chatClient.prompt()
                .messages(new SystemMessage(SYSTEM_PROMPT), new UserMessage(prompt))
                .call()
                .content();

            long elapsed = java.time.Duration.between(start, Instant.now()).toMillis();
            ctx.observation().onResponse("PlannerAgent", response, elapsed,
                AgentResult.TokenUsage.ZERO);

            var plan = parsePlan(response);
            ctx.observation().onDecision("PlannerAgent", "plan_created",
                plan.items().size() + " items, outline sections: " + countOutlineSections(plan.topicOutline()));

            return AgentResult.of(plan, AgentResult.TokenUsage.ZERO, elapsed,
                Map.of("agent", "PlannerAgent", "itemCount", plan.items().size(),
                       "replan", feedback != null));

        } catch (Exception e) {
            log.error("PlannerAgent failed: {}", e.getMessage());
            ctx.observation().onError("PlannerAgent", e);
            return AgentResult.error("Plan generation failed: " + e.getMessage());
        }
    }

    private String buildSourcesSummary(List<ResourceGenerationState.SourceItem> sources) {
        if (sources == null || sources.isEmpty()) return "No sources available";
        return sources.stream()
            .limit(10)
            .map(s -> s.title() + ": " + s.locator())
            .collect(Collectors.joining("; "));
    }

    private ResourceGenerationState.ResourcePlan parsePlan(String json) {
        try {
            var node = mapper.readTree(json);
            List<ResourceGenerationState.ResourcePlanItem> items =
                mapper.convertValue(node.get("items"),
                    mapper.getTypeFactory().constructCollectionType(List.class,
                        ResourceGenerationState.ResourcePlanItem.class));
            return new ResourceGenerationState.ResourcePlan(
                node.get("topicOutline").asText(),
                items,
                mapper.convertValue(node.get("pushReason"), List.class),
                mapper.convertValue(node.get("queries"), List.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse plan JSON: " + e.getMessage(), e);
        }
    }

    private int countOutlineSections(String outline) {
        return (int) outline.lines().filter(l -> l.startsWith("##")).count();
    }
}
