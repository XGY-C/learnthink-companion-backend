package com.learnthink.core.agent.impl;

import com.learnthink.core.agent.framework.AgentContext;
import com.learnthink.core.agent.framework.AgentResult;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import com.learnthink.core.config.PromptLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class PlannerAgent {

    private static final Logger log = LoggerFactory.getLogger(PlannerAgent.class);
    private final ChatClient chatClient;
    private final PromptLoader promptLoader;
    private final ObjectMapper mapper = new ObjectMapper();

    public PlannerAgent(@Qualifier("reasoningChatClientBuilder") ChatClient.Builder chatClientBuilder,
                        PromptLoader promptLoader) {
        this.chatClient = chatClientBuilder.build();
        this.promptLoader = promptLoader;
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

        String systemPrompt = promptLoader.get("agent/planner");
        ctx.observation().onPrompt("PlannerAgent", systemPrompt,
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
                .messages(new SystemMessage(systemPrompt), new UserMessage(prompt))
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
