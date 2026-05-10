package com.learnthink.core.agent.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.core.agent.framework.AgentContext;
import com.learnthink.core.agent.framework.AgentResult;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import com.learnthink.core.domain.entity.ProfileVersion;
import com.learnthink.core.repository.ProfileVersionMapper;
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
 * Extracts a structured learning profile summary from the user's profile data.
 * Reads from DB via profile service, produces a {@link ResourceGenerationState.ProfileSummary}.
 */
@Component
public class ProfileAgent {

    private static final Logger log = LoggerFactory.getLogger(ProfileAgent.class);
    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = """
        You are the profile analysis agent for LearnThink Companion.
        Given a student's profile dimensions, extract a concise summary.

        Output strictly as JSON:
        {
          "weakTop": ["weakness1", "weakness2", "weakness3"],
          "style": ["preference1", "preference2"],
          "minutesPerDay": 30,
          "goal": "short goal summary",
          "dimensionCount": 8
        }

        Rules:
        - weakTop: top 3 weakest knowledge areas
        - style: learning style preferences (e.g., "visual", "code_examples", "theoretical")
        - minutesPerDay: estimated daily study time
        - goal: one-sentence learning goal
        - dimensionCount: total number of profile dimensions populated (minimum 6 required)
        """;

    public ProfileAgent(ChatClient.Builder chatClientBuilder, ProfileVersionMapper profileVersionMapper) {
        this.chatClient = chatClientBuilder.build();
        this.profileVersionMapper = profileVersionMapper;
    }

    private final ProfileVersionMapper profileVersionMapper;

    public AgentResult<ResourceGenerationState.ProfileSummary> summarize(
        String userId, String courseId, int profileVersion, AgentContext ctx) {

        Instant start = Instant.now();
        ctx.observation().onPrompt(this.getClass().getSimpleName(), SYSTEM_PROMPT,
            Map.of("userId", userId, "courseId", courseId, "version", profileVersion));

        try {
            // In production, read profile from DB
            String profilesJson = loadProfileJson(userId, courseId, profileVersion);

            String response = chatClient.prompt()
                .messages(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage("Profile data (JSON): " + profilesJson)
                )
                .call()
                .content();

            long elapsed = java.time.Duration.between(start, Instant.now()).toMillis();
            ctx.observation().onResponse(this.getClass().getSimpleName(), response, elapsed,
                AgentResult.TokenUsage.ZERO);

            // Parse response
            var summary = parseSummary(response);
            if (summary.dimensionCount() < 6) {
                return AgentResult.error("Profile has fewer than 6 dimensions populated");
            }

            return AgentResult.of(summary, AgentResult.TokenUsage.ZERO, elapsed,
                Map.of("agent", "ProfileAgent", "version", profileVersion));

        } catch (Exception e) {
            log.error("ProfileAgent failed: {}", e.getMessage());
            ctx.observation().onError(this.getClass().getSimpleName(), e);
            return AgentResult.error("Profile analysis failed: " + e.getMessage());
        }
    }

    private String loadProfileJson(String userId, String courseId, int profileVersion) {
        ProfileVersion pv = profileVersionMapper.selectOne(
            new LambdaQueryWrapper<ProfileVersion>()
                .eq(ProfileVersion::getUserId, userId)
                .eq(ProfileVersion::getCourseId, courseId)
                .eq(ProfileVersion::getVersion, profileVersion));
        return pv != null ? pv.getDimensionsJson() : "{}";
    }

    private ResourceGenerationState.ProfileSummary parseSummary(String json) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(json);
            return new ResourceGenerationState.ProfileSummary(
                mapper.convertValue(node.get("weakTop"), List.class),
                mapper.convertValue(node.get("style"), List.class),
                node.get("minutesPerDay").asInt(),
                node.get("goal").asText(),
                node.get("dimensionCount").asInt()
            );
        } catch (Exception e) {
            log.warn("Failed to parse ProfileSummary, using defaults: {}", e.getMessage());
            return new ResourceGenerationState.ProfileSummary(
                List.of(), List.of(), 30, "Unknown", 0);
        }
    }
}
