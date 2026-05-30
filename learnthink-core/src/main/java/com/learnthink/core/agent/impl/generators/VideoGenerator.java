package com.learnthink.core.agent.impl.generators;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.impl.AutonomousGenerator;
import com.learnthink.core.agent.impl.RagTool;
import com.learnthink.core.agent.runtime.AgentContext;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import com.learnthink.core.domain.dto.ExplanationVideoDTO;
import com.learnthink.core.domain.dto.ProjectInput;
import com.learnthink.core.service.ExplanationVideoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 讲解视频生成器——将视频生成委派给 {@link ExplanationVideoService}
 * <p>为保持与其他生成器的类型一致性而继承 {@link AutonomousGenerator}，
 * 但跳过了自主 LLM 循环——视频制作使用专用的多步骤流水线
 *（脚本 → 场景 → TTS → Manim 渲染）。</p>
 */
@Component
public class VideoGenerator extends AutonomousGenerator implements TypeGenerator {

    private static final Logger log = LoggerFactory.getLogger(VideoGenerator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExplanationVideoService explanationVideoService;

    public VideoGenerator(
            @Qualifier("generationChatClientBuilder") ChatClient.Builder chatClientBuilder,
            RagTool ragTool,
            ExplanationVideoService explanationVideoService) {
        super(chatClientBuilder.build(), List.of(), ragTool);
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
            ProjectInput input = buildProjectInput(item, profile);
            String userId = context.userId();

            log.info("Calling ExplanationVideoService.generateVideo() for topic={}, userId={}",
                input.getTopic(), userId);
            ExplanationVideoDTO video = explanationVideoService.generateVideo(input, userId);
            log.info("Video task submitted: title={}, manimTaskId={}, duration={}s",
                video.getTitle(), video.getManimTaskId(), video.getDuration());

            var contentNode = MAPPER.createObjectNode()
                .put("title", video.getTitle())
                .put("duration", video.getDuration() != null ? video.getDuration() : 0)
                .put("type", "explanation_video")
                .put("manimTaskId", video.getManimTaskId());
            if (video.getVideoUrl() != null) {
                contentNode.put("videoUrl", video.getVideoUrl());
            }
            String contentJson = contentNode.toString();

            return new ResourceGenerationState.GeneratedContent(
                video.getTitle(), contentJson, "application/json", sources,
                forceLowConfidence ? "low" : "medium",
                Map.of("generator", "VideoGenerator",
                       "manimTaskId", video.getManimTaskId() != null ? video.getManimTaskId() : "",
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
        return generate(item, sources, profile, forceLowConfidence, reviewFeedback, context);
    }

    // ---- AutonomousGenerator abstract methods (unused — video delegates to ExplanationVideoService) ----

    @Override
    protected String doGenerate(GenerationTask task, List<ResourceGenerationState.SourceItem> sources, AgentContext ctx) {
        throw new UnsupportedOperationException("VideoGenerator uses ExplanationVideoService, not LLM generation");
    }

    @Override
    protected String doRevise(GenerationTask task, List<ResourceGenerationState.SourceItem> sources,
                              String currentContent, String reviewFeedback, AgentContext ctx) {
        throw new UnsupportedOperationException("VideoGenerator uses ExplanationVideoService, not LLM revision");
    }

    @Override
    protected String getSelfReviewSystemPrompt(String resourceType) {
        return "";
    }

    @Override
    protected boolean shouldRetrieveMore(GenerationTask task, List<ResourceGenerationState.SourceItem> currentSources) {
        return false;
    }

    @Override
    protected String getGenerationSystemPrompt(GenerationTask task, List<ResourceGenerationState.SourceItem> sources) {
        return "";
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
