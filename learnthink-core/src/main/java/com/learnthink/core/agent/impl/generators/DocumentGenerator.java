package com.learnthink.core.agent.impl.generators;

import com.learnthink.core.agent.impl.AutonomousGenerator;
import com.learnthink.core.agent.impl.RagTool;
import com.learnthink.core.agent.runtime.AgentContext;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import com.learnthink.core.agent.tools.visual.VisualCodeExtractor;
import com.learnthink.core.config.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class DocumentGenerator extends AutonomousGenerator implements TypeGenerator {

    private static final Logger log = LoggerFactory.getLogger(DocumentGenerator.class);
    private final PromptLoader promptLoader;

    public DocumentGenerator(
            @Qualifier("generationChatClientBuilder") ChatClient.Builder chatClientBuilder,
            RagTool ragTool,
            PromptLoader promptLoader) {
        super(
            chatClientBuilder.defaultOptions(OpenAiChatOptions.builder().temperature(0.5).build()).build(),
            List.of(),  // ToolCallback built per-call via buildRagToolCallback()
            ragTool);
        this.promptLoader = promptLoader;
    }

    @Override public String type() { return "doc"; }

    @Override
    public ResourceGenerationState.GeneratedContent generate(
            ResourceGenerationState.ResourcePlanItem item,
            List<ResourceGenerationState.SourceItem> typeSources,
            ResourceGenerationState.ProfileSummary profile,
            boolean forceLowConfidence,
            String reviewFeedback,
            AgentContext ctx) {

        GenerationTask task = new GenerationTask(
            "DocumentGenerator", "doc", item.title(), item.title(),
            item.keyPoints(), item.difficulty(), item.personalizationNote(),
            profile != null ? profile.style() : List.of(),
            profile != null ? profile.weakTop() : List.of(),
            reviewFeedback, typeSources);

        GenerationResult result = runAutonomousLoop(task, ctx);

        if (result.content() == null) {
            throw new RuntimeException("Document generation failed for: " + item.title());
        }

        String finalContent = VisualCodeExtractor.unfoldInlineSvg(result.content())
            .replaceAll("\\[source:\\s*[^\\]]+\\]", "");
        if (forceLowConfidence && result.confidence() < 0.6) {
            finalContent = "> ⚠️ Low confidence: evidence for this topic is limited.\n\n" + finalContent;
        }

        return new ResourceGenerationState.GeneratedContent(
            item.title(), finalContent, "text/markdown", result.sources(),
            result.confidence() >= 0.8 ? "high" : result.confidence() >= 0.5 ? "medium" : "low",
            Map.of("generator", "DocumentGenerator", "autonomous", true,
                "confidence", result.confidence(), "selfReviewPassed", result.selfReviewPassed(),
                "sourceCount", result.sources().size()));
    }

    @Override
    public ResourceGenerationState.GeneratedContent revise(
            ResourceGenerationState.ResourcePlanItem item,
            List<ResourceGenerationState.SourceItem> typeSources,
            ResourceGenerationState.ProfileSummary profile,
            boolean forceLowConfidence,
            String reviewFeedback,
            ResourceGenerationState.GeneratedContent original,
            AgentContext ctx) {

        String systemPrompt = getGenerationPrompt(item, profile)
            + "\n\n## 定向修改要求\n" + reviewFeedback;
        String userMsg = "需修改的内容：\n"
            + (original.content() != null ? original.content() : "");

        String content = chatClient.prompt()
            .messages(new SystemMessage(systemPrompt), new UserMessage(userMsg))
            .call().content();

        log.info("[AI-RESPONSE][DocumentGenerator] revise length={} chars\n{}",
            content != null ? content.length() : 0,
            content != null ? content.substring(0, Math.min(2000, content.length())) : "null");

        content = VisualCodeExtractor.unfoldInlineSvg(content)
            .replaceAll("\\[source:\\s*[^\\]]+\\]", "");

        return new ResourceGenerationState.GeneratedContent(
            item.title(), content, "text/markdown", typeSources,
            forceLowConfidence ? "low" : "medium",
            Map.of("generator", "DocumentGenerator", "revised", true));
    }

    // ---- AutonomousGenerator abstract methods ----

    @Override
    protected String doGenerate(GenerationTask task,
                                List<ResourceGenerationState.SourceItem> sources,
                                AgentContext ctx) {
        String systemPrompt = getGenerationSystemPrompt(task, sources);
        String userMsg = buildUserMessage(task, sources);

        return chatClient.prompt()
            .messages(new SystemMessage(systemPrompt), new UserMessage(userMsg))
            .toolCallbacks(buildRagToolCallback(ctx))
            .call().content();
    }

    @Override
    protected String doRevise(GenerationTask task,
                              List<ResourceGenerationState.SourceItem> sources,
                              String currentContent, String reviewFeedback,
                              AgentContext ctx) {
        String systemPrompt = getGenerationSystemPrompt(task, sources)
            + "\n\n## 修改指令\n" + reviewFeedback;
        return chatClient.prompt()
            .messages(new SystemMessage(systemPrompt),
                      new UserMessage("需要修改的内容：\n" + currentContent))
            .toolCallbacks(buildRagToolCallback(ctx))
            .call().content();
    }

    @Override
    protected String getSelfReviewSystemPrompt(String resourceType) {
        return promptLoader.get("agent/self_review_doc");
    }

    @Override
    protected boolean shouldRetrieveMore(GenerationTask task,
                                          List<ResourceGenerationState.SourceItem> currentSources) {
        return currentSources.size() < 3;
    }

    @Override
    protected String getGenerationSystemPrompt(GenerationTask task,
                                                List<ResourceGenerationState.SourceItem> sources) {
        return getGenerationPrompt(task);
    }

    // ---- Private helpers ----

    private String getGenerationPrompt(GenerationTask task) {
        return promptLoader.get("generator/document")
            .replace("{difficulty}", task.difficulty())
            .replace("{personalization_note}", task.personalizationNote() != null ? task.personalizationNote() : "")
            .replace("{style}", task.style() != null ? String.join("、", task.style()) : "")
            .replace("{weakTop}", task.weakTop() != null ? String.join("、", task.weakTop()) : "");
    }

    private String getGenerationPrompt(ResourceGenerationState.ResourcePlanItem item,
                                        ResourceGenerationState.ProfileSummary profile) {
        return promptLoader.get("generator/document")
            .replace("{difficulty}", item.difficulty())
            .replace("{personalization_note}", item.personalizationNote())
            .replace("{style}", profile != null ? String.join("、", profile.style()) : "")
            .replace("{weakTop}", profile != null ? String.join("、", profile.weakTop()) : "");
    }

    private String buildUserMessage(GenerationTask task,
                                     List<ResourceGenerationState.SourceItem> sources) {
        String sourcesText = sources.stream()
            .map(s -> {
                String book = s.bookTitle() != null && !s.bookTitle().isBlank() ? "《" + s.bookTitle() + "》" : "";
                String chapter = s.chapterTitle() != null && !s.chapterTitle().isBlank() ? s.chapterTitle() : "";
                String tag = book + chapter;
                String ref = tag.isBlank() ? s.docId() : tag;
                return String.format("[%s] %s — %s", ref, s.quote(), s.locator());
            })
            .collect(Collectors.joining("\n"));

        return String.format("""
            Topic: %s
            Title: %s
            Key points to cover: %s
            Sources:\n%s
            """,
            task.title(), task.title(),
            String.join(", ", task.keyPoints()),
            sourcesText.isEmpty() ? "(no sources — use RAG tool to search for relevant information)" : sourcesText);
    }

    /** 使用 AgentContext 中的 courseId 构建 DelegatingToolCallback */
    private ToolCallback buildRagToolCallback(AgentContext ctx) {
        if (ragTool == null) return null;
        return new com.learnthink.core.agent.tools.callbacks.DelegatingToolCallback(
            ragTool, java.util.Map.of("course_id", ctx.courseId()));
    }
}
