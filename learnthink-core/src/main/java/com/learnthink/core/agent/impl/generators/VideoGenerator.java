package com.learnthink.core.agent.impl.generators;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.framework.AgentContext;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import com.learnthink.core.domain.dto.ExplanationVideoDTO;
import com.learnthink.core.domain.dto.ProjectInput;
import com.learnthink.core.service.ExplanationVideoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Generates explanation videos by delegating to {@link ExplanationVideoService}.
 *
 * <p>Unlike text-based generators that produce content via LLM directly,
 * this generator invokes the full video production pipeline:
 * <ol>
 *   <li>AI script generation</li>
 *   <li>AI scene storyboard generation</li>
 *   <li>Parallel TTS audio synthesis</li>
 *   <li>Manim rendering via external API</li>
 * </ol>
 *
 * <p>The generated content is stored as JSON containing the video URL and metadata,
 * enabling the frontend to embed or link the rendered video directly.
 */
@Component
public class VideoGenerator implements TypeGenerator {

    private static final Logger log = LoggerFactory.getLogger(VideoGenerator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExplanationVideoService explanationVideoService;

    public VideoGenerator(ExplanationVideoService explanationVideoService) {
        this.explanationVideoService = explanationVideoService;
    }

    @Override
    public String type() { return "video"; }

    @Override
    public boolean requiresSourceCoverage() { return false; }

    @Override
    public ResourceGenerationState.GeneratedContent generate(
        ResourceGenerationState.ResourcePlanItem item,
        List<ResourceGenerationState.SourceItem> sources,
        ResourceGenerationState.ProfileSummary profile,
        boolean forceLowConfidence,
        String reviewFeedback,
        AgentContext context) {

        log.info("=== VideoGenerator START === title={}, feedback={}",
            item.title(), reviewFeedback != null ? "with feedback" : "initial");

        try {
            // Build ProjectInput from planner item + student profile
            ProjectInput input = buildProjectInput(item, profile);
            String userId = context.userId();

            log.info("Calling ExplanationVideoService.generateVideo() for topic={}, userId={}",
                input.getTopic(), userId);
            ExplanationVideoDTO video = explanationVideoService.generateVideo(input, userId);
            log.info("Video generation succeeded: title={}, url={}, duration={}s",
                video.getTitle(), video.getVideoUrl(), video.getDuration());

            // Serialize video metadata as JSON content
            String contentJson = MAPPER.createObjectNode()
                .put("videoUrl", video.getVideoUrl())
                .put("title", video.getTitle())
                .put("duration", video.getDuration() != null ? video.getDuration() : 0)
                .put("type", "explanation_video")
                .toString();

            return new ResourceGenerationState.GeneratedContent(
                video.getTitle(), contentJson, "application/json", sources,
                forceLowConfidence ? "low" : "medium",
                Map.of("generator", "VideoGenerator",
                       "videoUrl", video.getVideoUrl(),
                       "duration", video.getDuration())
            );

        } catch (Exception e) {
            log.error("Video generation failed for title={}: {}", item.title(), e.getMessage(), e);
            throw new RuntimeException("Video generation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ResourceGenerationState.GeneratedContent revise(
        ResourceGenerationState.ResourcePlanItem item,
        List<ResourceGenerationState.SourceItem> sources,
        ResourceGenerationState.ProfileSummary profile,
        boolean forceLowConfidence,
        String reviewFeedback,
        ResourceGenerationState.GeneratedContent original,
        AgentContext context) {

        log.info("=== VideoGenerator REVISE === title={}, feedback={}", item.title(), reviewFeedback);
        // Video rendering does not support targeted revision — regenerate fully
        return generate(item, sources, profile, forceLowConfidence, reviewFeedback, context);
    }

    private static ProjectInput buildProjectInput(
        ResourceGenerationState.ResourcePlanItem item,
        ResourceGenerationState.ProfileSummary profile) {

        ProjectInput input = new ProjectInput();
        input.setTopic(item.title());
        input.setGoal(profile != null ? profile.goal() : "帮助学习者理解该主题的核心概念");
        input.setOutline(item.keyPoints() != null ? String.join("\n", item.keyPoints()) : "");
        input.setStyle(profile != null && profile.style() != null
            ? String.join("、", profile.style()) : "通俗易懂");
        input.setAudience(profile != null && profile.style() != null
            ? String.join("、", profile.style()) : "普通学习者");

        int durationSec = item.estimatedMinutes() > 0 ? item.estimatedMinutes() * 60 : 90;
        input.setTargetDurationSec(durationSec);

        input.setLanguage("zh-CN");
        return input;
    }
}
