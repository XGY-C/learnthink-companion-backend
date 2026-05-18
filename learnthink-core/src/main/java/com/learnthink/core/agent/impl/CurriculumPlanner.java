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
public class CurriculumPlanner {

    private static final Logger log = LoggerFactory.getLogger(CurriculumPlanner.class);
    private final ChatClient chatClient;
    private final PromptLoader promptLoader;
    private final ObjectMapper mapper = new ObjectMapper();

    public CurriculumPlanner(@Qualifier("reasoningChatClientBuilder") ChatClient.Builder chatClientBuilder,
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

        log.info("=== CurriculumPlanner START === topic={}, types={}, feedback={}",
                topic, resourceTypes, feedback != null ? "with feedback" : "initial");
        Instant start = Instant.now();
        String contextInfo = feedback != null
            ? "REPLANNING with feedback: " + feedback
            : "Initial planning";

        String systemPrompt = promptLoader.get("agent/planner");
        ctx.observation().onPrompt("CurriculumPlanner", systemPrompt,
            Map.of("topic", topic, "types", resourceTypes, "replanning", feedback != null));

        try {
            String sourcesSummary = buildSourcesSummary(mergedSources);
            log.info("Sources summary: {}", sourcesSummary.substring(0, Math.min(100, sourcesSummary.length())));
            String profileSummaryText = buildProfileSummaryText(profile);
            log.info("Profile summary prepared ({} chars)", profileSummaryText.length());

            String prompt = String.format("""
                {profile_summary}: %s
                {merged_sources_summary}: %s
                {topic}: %s
                {resource_types}: %s
                {plan_feedback}: %s
                Context: %s
                """,
                profileSummaryText, sourcesSummary, topic,
                String.join(", ", resourceTypes),
                feedback != null ? feedback : "N/A (initial plan)",
                contextInfo);

            log.info("Calling LLM for planning");
            String response = chatClient.prompt()
                .messages(new SystemMessage(systemPrompt), new UserMessage(prompt))
                .call()
                .content();

            long elapsed = java.time.Duration.between(start, Instant.now()).toMillis();
            log.info("LLM call completed in {}ms", elapsed);
            ctx.observation().onResponse("CurriculumPlanner", response, elapsed,
                AgentResult.TokenUsage.ZERO);

            var plan = parsePlan(response);
            log.info("Plan parsed successfully. SubTopics: {}, Items: {}, Outline sections: {}",
                    plan.subTopics().size(), plan.items().size(), countOutlineSections(plan.topicOutline()));
            ctx.observation().onDecision("CurriculumPlanner", "plan_created",
                plan.subTopics().size() + " sub-topics, " + plan.items().size() + " items");

            log.info("CurriculumPlanner completed successfully");
            return AgentResult.of(plan, AgentResult.TokenUsage.ZERO, elapsed,
                Map.of("agent", "CurriculumPlanner", "itemCount", plan.items().size(),
                       "replan", feedback != null));

        } catch (Exception e) {
            log.error("CurriculumPlanner failed: {}", e.getMessage(), e);
            ctx.observation().onError("CurriculumPlanner", e);
            return AgentResult.error("Plan generation failed: " + e.getMessage());
        }
    }

    private String buildProfileSummaryText(ResourceGenerationState.ProfileSummary profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("\nStudent Profile:");
        sb.append("\n- Weak areas: ").append(profile.weakTop());
        sb.append("\n- Learning style: ").append(profile.style());
        sb.append("\n- Time per day: ").append(profile.minutesPerDay()).append(" min");
        sb.append("\n- Goal: ").append(profile.goal());
        sb.append("\n- Dimensions covered: ").append(profile.dimensionCount()).append("/7");

        if (profile.currentChapter() != null && !profile.currentChapter().isBlank()) {
            sb.append("\n- Current chapter: ").append(profile.currentChapter());
        }

        // v4.0: KP anchors grouped by scope
        List<ResourceGenerationState.KpAnchor> anchors = profile.kpAnchors();
        if (anchors != null && !anchors.isEmpty()) {
            var coreGaps = anchors.stream()
                .filter(a -> "core_curriculum".equals(a.scope()) && "weak".equals(a.relationType()))
                .toList();
            var prereqGaps = anchors.stream()
                .filter(a -> "prerequisite".equals(a.scope()) && "weak".equals(a.relationType()))
                .toList();
            var coreInterests = anchors.stream()
                .filter(a -> "interest".equals(a.relationType())
                    && !"extracurricular".equals(a.scope()))
                .toList();
            var extraInterests = anchors.stream()
                .filter(a -> "extracurricular".equals(a.scope()))
                .toList();

            if (!coreGaps.isEmpty()) {
                sb.append("\n- Core curriculum gaps (HIGHEST priority):");
                for (var a : coreGaps) {
                    sb.append("\n  . ").append(a.kpName());
                    if (a.chapterTitle() != null) sb.append(" [").append(a.chapterTitle()).append("]");
                    sb.append(" (confidence: ").append(String.format("%.0f%%", a.confidence() * 100)).append(")");
                }
            }
            if (!prereqGaps.isEmpty()) {
                sb.append("\n- Prerequisite gaps (address only if needed for current chapter):");
                for (var a : prereqGaps) {
                    sb.append("\n  . ").append(a.kpName());
                    if (a.chapterTitle() != null) sb.append(" [").append(a.chapterTitle()).append("]");
                }
            }
            if (!coreInterests.isEmpty()) {
                sb.append("\n- In-curriculum interests (low priority):");
                for (var a : coreInterests) {
                    sb.append("\n  . ").append(a.kpName());
                    sb.append(" [").append(a.scope()).append("]");
                }
            }
            if (!extraInterests.isEmpty()) {
                sb.append("\n- Extracurricular interests (reading only, do NOT make core resources):");
                for (var a : extraInterests) {
                    sb.append("\n  . ").append(a.kpName());
                }
            }
        }
        return sb.toString();
    }

    private String buildSourcesSummary(List<ResourceGenerationState.SourceItem> sources) {
        if (sources == null || sources.isEmpty()) return "No sources available";
        return sources.stream()
            .limit(10)
            .map(s -> {
                String book = s.bookTitle() != null && !s.bookTitle().isBlank() ? "《" + s.bookTitle() + "》" : "";
                String chapter = s.chapterTitle() != null && !s.chapterTitle().isBlank() ? s.chapterTitle() : "";
                String bookType = s.bookType() != null && !s.bookType().isBlank() ? "[" + s.bookType() + "]" : "";
                String ref = bookType + book + chapter;
                return (ref.isBlank() ? s.docId() : ref) + ": " + s.locator();
            })
            .collect(Collectors.joining("; "));
    }

    private ResourceGenerationState.ResourcePlan parsePlan(String json) {
        try {
            var node = mapper.readTree(json);

            // Parse sub-topics (new format); fallback to single-topic wrapper if absent
            List<ResourceGenerationState.SubTopic> subTopics;
            if (node.has("subTopics") && node.get("subTopics").isArray()) {
                subTopics = mapper.convertValue(node.get("subTopics"),
                    mapper.getTypeFactory().constructCollectionType(List.class,
                        ResourceGenerationState.SubTopic.class));
            } else {
                subTopics = List.of(new ResourceGenerationState.SubTopic(
                    0, node.get("topicOutline").asText("Overview"),
                    "Full topic (legacy format — no sub-topic decomposition)",
                    List.of(), 60, "medium"));
            }

            // Parse items with subTopicIndex (default 0 if missing)
            List<ResourceGenerationState.ResourcePlanItem> items =
                mapper.convertValue(node.get("items"),
                    mapper.getTypeFactory().constructCollectionType(List.class,
                        ResourceGenerationState.ResourcePlanItem.class));
            // Normalize type names: LLM may output "document" instead of "doc"
            items = items.stream()
                .map(item -> {
                    ResourceGenerationState.ResourcePlanItem normalized =
                        "document".equals(item.type())
                        ? new ResourceGenerationState.ResourcePlanItem(
                            "doc", item.title(), item.difficulty(), item.estimatedMinutes(),
                            item.format(), item.keyPoints(), item.personalizationNote(),
                            item.subTopicIndex())
                        : item;
                    return normalized;
                })
                .toList();

            return new ResourceGenerationState.ResourcePlan(
                node.get("topicOutline").asText(),
                subTopics,
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
