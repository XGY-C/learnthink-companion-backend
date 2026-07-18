package com.learnthink.core.agent.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.profile.ProfileMdSet;
import com.learnthink.core.agent.runtime.AgentContext;
import com.learnthink.core.agent.runtime.AgentResult;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import com.learnthink.core.config.PromptLoader;
import com.learnthink.core.domain.entity.ProfileVersion;
import com.learnthink.core.repository.ProfileVersionMapper;
import com.learnthink.common.util.LlmJson;
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
        String systemPrompt = promptLoader.get("agent/profile_summary_from_md");
        ctx.observation().onPrompt(this.getClass().getSimpleName(), systemPrompt,
            Map.of("userId", userId, "courseId", courseId, "version", profileVersion));

        try {
            ProfileMdSet mdSet = loadProfileMd(userId, courseId, profileVersion);

            String userPrompt = String.format(
                "=== 核心画像 ===\n%s\n\n=== 学习风格画像 ===\n%s\n\n=== 知识掌握画像 ===\n%s",
                mdSet.getCoreProfileMd(),
                mdSet.getLearningProfileMd(),
                mdSet.getKnowledgeProfileMd());

            log.info("Loaded profile MD (core: {} chars, learning: {} chars, knowledge: {} chars)",
                mdSet.getCoreProfileMd().length(),
                mdSet.getLearningProfileMd().length(),
                mdSet.getKnowledgeProfileMd().length());

            if (mdSet.getCoreProfileMd().isBlank()
                    && mdSet.getLearningProfileMd().isBlank()
                    && mdSet.getKnowledgeProfileMd().isBlank()) {
                log.warn("Profile MD is empty for userId={}, courseId={}, version={}",
                    userId, courseId, profileVersion);
                return AgentResult.error("Profile not yet generated - profile MD is empty");
            }

            String response = chatClient.prompt()
                .messages(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)
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

            String currentChapter = extractCurrentChapter(mdSet);
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

    private ProfileMdSet loadProfileMd(String userId, String courseId, int profileVersion) {
        ProfileVersion pv = profileVersionMapper.selectOne(
            new LambdaQueryWrapper<ProfileVersion>()
                .eq(ProfileVersion::getUserId, userId)
                .eq(ProfileVersion::getCourseId, courseId)
                .eq(ProfileVersion::getVersion, profileVersion));

        if (pv == null) {
            return new ProfileMdSet("", "", "", "{}");
        }

        String coreMd = pv.getCoreProfileMd();
        String learningMd = pv.getLearningProfileMd();
        String knowledgeMd = pv.getKnowledgeProfileMd();

        return new ProfileMdSet(
            coreMd != null ? coreMd : "",
            learningMd != null ? learningMd : "",
            knowledgeMd != null ? knowledgeMd : "",
            pv.getDisplayJson() != null ? pv.getDisplayJson() : "{}"
        );
    }

    private String extractCurrentChapter(ProfileMdSet mdSet) {
        if (mdSet.getCoreProfileMd() == null) return null;
        // 从 core_profile_md 中查找章节信息
        Map<String, String> parsed = parseMdKeys(mdSet.getCoreProfileMd());
        String chapter = parsed.get("core.current_chapter");
        if (chapter != null && !chapter.isBlank()) return chapter;

        // 也检查 knowledge_profile_md 中的科目
        if (mdSet.getKnowledgeProfileMd() != null) {
            var lines = mdSet.getKnowledgeProfileMd().split("\n");
            for (String line : lines) {
                if (line.trim().startsWith("## ")) {
                    return line.trim().substring(3).trim();
                }
            }
        }
        return null;
    }

    private Map<String, String> parseMdKeys(String mdText) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "- \\[([^\\]]+)\\]\\s*(.*?)(?=\\n- \\[|\\n#|\\n##|\\z)",
            java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(mdText);
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        while (matcher.find()) {
            result.put(matcher.group(1).trim(), matcher.group(2).trim());
        }
        return result;
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

    private ResourceGenerationState.ProfileSummary parseSummary(String raw) {
        JsonNode node = LlmJson.readTree(raw);
        if (node == null) {
            log.warn("Failed to parse ProfileSummary (invalid JSON), using defaults");
            return new ResourceGenerationState.ProfileSummary(
                List.of(), List.of(), 30, "Unknown", 0, null, List.of());
        }

        List<String> weakTop = objectMapper.convertValue(
            node.get("weak_top"), List.class);
        List<String> style = objectMapper.convertValue(
            node.get("style"), List.class);
        String goal = node.has("goal") ? node.get("goal").asText() : "Unknown";
        int minutesPerDay = extractMinutesPerDay(node);

        return new ResourceGenerationState.ProfileSummary(
            weakTop != null ? weakTop : List.of(),
            style != null ? style : List.of(),
            minutesPerDay, goal, 7, null, List.of());
    }

    private int extractMinutesPerDay(JsonNode node) {
        try {
            JsonNode pref = node.get("preference");
            if (pref != null && pref.has("pace")) {
                String pace = pref.get("pace").asText();
                var m = java.util.regex.Pattern.compile("(\\d+)\\s*小时").matcher(pace);
                if (m.find()) return Integer.parseInt(m.group(1)) * 60;
                m = java.util.regex.Pattern.compile("(\\d+)\\s*分钟").matcher(pace);
                if (m.find()) return Integer.parseInt(m.group(1));
            }
            if (node.has("minutesPerDay") && node.get("minutesPerDay").canConvertToInt()) {
                return node.get("minutesPerDay").asInt();
            }
        } catch (Exception ignored) {}
        return 30;
    }
}
