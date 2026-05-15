package com.learnthink.core.agent.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.core.agent.framework.AgentContext;
import com.learnthink.core.agent.framework.AgentResult;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import com.learnthink.core.domain.entity.ProfileVersion;
import com.learnthink.core.repository.ProfileVersionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.learnthink.core.config.PromptLoader;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class ProfileAgent {

    private static final Logger log = LoggerFactory.getLogger(ProfileAgent.class);
    private final ChatClient chatClient;
    private final PromptLoader promptLoader;

    public ProfileAgent(ChatClient.Builder chatClientBuilder,
                        ProfileVersionMapper profileVersionMapper,
                        PromptLoader promptLoader) {
        this.chatClient = chatClientBuilder.build();
        this.profileVersionMapper = profileVersionMapper;
        this.promptLoader = promptLoader;
    }

    private final ProfileVersionMapper profileVersionMapper;

    public AgentResult<ResourceGenerationState.ProfileSummary> summarize(
        String userId, String courseId, int profileVersion, AgentContext ctx) {

        log.info("=== ProfileAgent START === userId={}, courseId={}, version={}", userId, courseId, profileVersion);
        Instant start = Instant.now();
        String systemPrompt = promptLoader.get("agent/profile");
        ctx.observation().onPrompt(this.getClass().getSimpleName(), systemPrompt,
            Map.of("userId", userId, "courseId", courseId, "version", profileVersion));

        try {
            String profilesJson = loadProfileJson(userId, courseId, profileVersion);
            log.info("Loaded profile JSON (length: {} chars)", profilesJson.length());

            String response = chatClient.prompt()
                .messages(
                    new SystemMessage(systemPrompt),
                    new UserMessage("Profile data (JSON): " + profilesJson)
                )
                .call()
                .content();

            long elapsed = java.time.Duration.between(start, Instant.now()).toMillis();
            log.info("LLM call completed in {}ms", elapsed);
            ctx.observation().onResponse(this.getClass().getSimpleName(), response, elapsed,
                AgentResult.TokenUsage.ZERO);

            // Parse response
            var summary = parseSummary(response);
            if (summary.dimensionCount() < 6) {
                log.warn("Profile has fewer than 6 dimensions populated: {}", summary.dimensionCount());
                return AgentResult.error("Profile has fewer than 6 dimensions populated");
            }

            log.info("ProfileAgent completed successfully. Dimensions: {}", summary.dimensionCount());
            return AgentResult.of(summary, AgentResult.TokenUsage.ZERO, elapsed,
                Map.of("agent", "ProfileAgent", "version", profileVersion));

        } catch (Exception e) {
            log.error("ProfileAgent failed: {}", e.getMessage(), e);
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
