package com.learnthink.core.agent.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentContext;
import com.learnthink.core.agent.runtime.AgentResult;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import com.learnthink.core.config.PromptLoader;
import com.learnthink.core.domain.entity.ProfileVersion;
import com.learnthink.core.repository.ProfileVersionMapper;
import com.learnthink.core.service.KpAnchorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ProfileAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ProfileAnalyzer.class);
    private final ChatClient chatClient;
    private final PromptLoader promptLoader;
    private final ProfileVersionMapper profileVersionMapper;
    private final KpAnchorService kpAnchorService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProfileAnalyzer(ChatClient.Builder chatClientBuilder,
                           ProfileVersionMapper profileVersionMapper,
                           PromptLoader promptLoader,
                           KpAnchorService kpAnchorService) {
        this.chatClient = chatClientBuilder.build();
        this.profileVersionMapper = profileVersionMapper;
        this.promptLoader = promptLoader;
        this.kpAnchorService = kpAnchorService;
    }

    public AgentResult<ResourceGenerationState.ProfileSummary> summarize(
        String userId, String courseId, int profileVersion,
        String profileVersionId, AgentContext ctx) {

        log.info("=== ProfileAnalyzer START === userId={}, courseId={}, version={}, pvId={}",
            userId, courseId, profileVersion, profileVersionId);
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
            log.info("[AI-RESPONSE][ProfileAnalyzer] summarize ({}ms) length={} chars\n{}",
                elapsed,
                response != null ? response.length() : 0,
                response != null ? response.substring(0, Math.min(2000, response.length())) : "null");
            ctx.observation().onResponse(this.getClass().getSimpleName(), response, elapsed,
                AgentResult.TokenUsage.ZERO);

            var summary = parseSummary(response);
            if (summary.dimensionCount() < 6) {
                log.warn("Profile has fewer than 6 dimensions populated: {}", summary.dimensionCount());
                return AgentResult.error("Profile has fewer than 6 dimensions populated");
            }

            // v4.0: Extract currentChapter from dimensions_json
            String currentChapter = extractCurrentChapter(profilesJson);
            // v4.0: Load KP anchors if available
            List<ResourceGenerationState.KpAnchor> kpAnchors = loadKpAnchors(profileVersionId);

            var enhancedSummary = new ResourceGenerationState.ProfileSummary(
                summary.weakTop(), summary.style(), summary.minutesPerDay(),
                summary.goal(), summary.dimensionCount(), currentChapter, kpAnchors);

            log.info("ProfileAnalyzer completed. Dimensions: {}, Chapter: {}, Anchors: {}",
                summary.dimensionCount(), currentChapter,
                kpAnchors != null ? kpAnchors.size() : 0);
            return AgentResult.of(enhancedSummary, AgentResult.TokenUsage.ZERO, elapsed,
                Map.of("agent", "ProfileAnalyzer", "version", profileVersion));

        } catch (Exception e) {
            log.error("ProfileAnalyzer failed: {}", e.getMessage(), e);
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

    private String extractCurrentChapter(String dimensionsJson) {
        try {
            JsonNode root = objectMapper.readTree(dimensionsJson);
            if (root.isArray()) {
                for (JsonNode dim : root) {
                    if ("major_context".equals(dim.path("key").asText())) {
                        JsonNode value = dim.path("value");
                        String chapter = value.path("current_chapter").asText();
                        if (!chapter.isBlank()) return chapter;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract currentChapter: {}", e.getMessage());
        }
        return null;
    }

    private List<ResourceGenerationState.KpAnchor> loadKpAnchors(String profileVersionId) {
        if (profileVersionId == null || profileVersionId.isBlank()) {
            return List.of();
        }
        try {
            return kpAnchorService.loadAnchors(profileVersionId);
        } catch (Exception e) {
            log.warn("Failed to load KP anchors for pv={}: {}", profileVersionId, e.getMessage());
            return List.of();
        }
    }

    private ResourceGenerationState.ProfileSummary parseSummary(String json) {
        try {
            var node = objectMapper.readTree(json);
            return new ResourceGenerationState.ProfileSummary(
                objectMapper.convertValue(node.get("weakTop"), List.class),
                objectMapper.convertValue(node.get("style"), List.class),
                node.get("minutesPerDay").asInt(),
                node.get("goal").asText(),
                node.get("dimensionCount").asInt(),
                null,    // currentChapter — filled from dimensions_json, not LLM response
                List.of() // kpAnchors — filled after DB lookup
            );
        } catch (Exception e) {
            log.warn("Failed to parse ProfileSummary, using defaults: {}", e.getMessage());
            return new ResourceGenerationState.ProfileSummary(
                List.of(), List.of(), 30, "Unknown", 0, null, List.of());
        }
    }
}
