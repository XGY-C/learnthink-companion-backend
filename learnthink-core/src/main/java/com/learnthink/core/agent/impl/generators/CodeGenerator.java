package com.learnthink.core.agent.impl.generators;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.impl.AutonomousGenerator;
import com.learnthink.core.agent.impl.RagTool;
import com.learnthink.core.agent.runtime.AgentContext;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import com.learnthink.core.config.PromptLoader;
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
public class CodeGenerator extends AutonomousGenerator implements TypeGenerator {

    private final PromptLoader promptLoader;
    private final ObjectMapper mapper = new ObjectMapper();

    public CodeGenerator(
            @Qualifier("generationChatClientBuilder") ChatClient.Builder chatClientBuilder,
            RagTool ragTool, PromptLoader promptLoader) {
        super(chatClientBuilder.defaultOptions(OpenAiChatOptions.builder().temperature(0.2).build()).build(),
            List.of(), ragTool);
        this.promptLoader = promptLoader;
    }

    @Override public String type() { return "code"; }

    @Override
    public ResourceGenerationState.GeneratedContent generate(
            ResourceGenerationState.ResourcePlanItem item,
            List<ResourceGenerationState.SourceItem> typeSources,
            ResourceGenerationState.ProfileSummary profile,
            boolean forceLowConfidence, String reviewFeedback, AgentContext ctx) {

        GenerationTask task = new GenerationTask("CodeGenerator", "code", item.title(), item.title(),
            item.keyPoints(), item.difficulty(), item.personalizationNote(),
            List.of(), List.of(), reviewFeedback, typeSources);

        GenerationResult result = runAutonomousLoop(task, ctx);
        if (result.content() == null) throw new RuntimeException("Code generation failed");

        String jsonContent = validateAndNormalize(result.content());

        return new ResourceGenerationState.GeneratedContent(item.title(), jsonContent,
            "application/json", result.sources(),
            result.confidence() >= 0.8 ? "high" : result.confidence() >= 0.5 ? "medium" : "low",
            Map.of("generator", "CodeGenerator", "autonomous", true, "confidence", result.confidence()));
    }

    @Override
    public ResourceGenerationState.GeneratedContent revise(
            ResourceGenerationState.ResourcePlanItem item,
            List<ResourceGenerationState.SourceItem> typeSources,
            ResourceGenerationState.ProfileSummary profile,
            boolean forceLowConfidence, String reviewFeedback,
            ResourceGenerationState.GeneratedContent original, AgentContext ctx) {
        String sp = getGenPrompt(item) + "\n\n## 修改\n" + reviewFeedback;
        String um = "需修改：\n" + (original.content() != null ? original.content() : "");
        String content = chatClient.prompt().messages(new SystemMessage(sp), new UserMessage(um)).call().content();
        log.info("[AI-RESPONSE][CodeGenerator] revise length={} chars\n{}",
            content != null ? content.length() : 0,
            content != null ? content.substring(0, Math.min(2000, content.length())) : "null");
        String jsonContent = validateAndNormalize(content);
        return new ResourceGenerationState.GeneratedContent(item.title(), jsonContent, "application/json", typeSources,
            forceLowConfidence ? "low" : "high", Map.of("generator", "CodeGenerator", "revised", true));
    }

    @Override protected String doGenerate(GenerationTask task, List<ResourceGenerationState.SourceItem> sources, AgentContext ctx) {
        return chatClient.prompt()
            .messages(new SystemMessage(getGenerationSystemPrompt(task, sources)),
                      new UserMessage(buildUserMsg(task, sources)))
            .toolCallbacks(buildRagCallback(ctx))
            .call().content();
    }
    @Override protected String doRevise(GenerationTask task, List<ResourceGenerationState.SourceItem> sources,
                                          String current, String feedback, AgentContext ctx) {
        return chatClient.prompt()
            .messages(new SystemMessage(getGenerationSystemPrompt(task, sources) + "\n\n## 修改\n" + feedback),
                      new UserMessage("需修改：\n" + (current.length() > 2000 ? current.substring(0, 2000) + "..." : current)))
            .toolCallbacks(buildRagCallback(ctx))
            .call().content();
    }
    private ToolCallback buildRagCallback(AgentContext ctx) {
        if (ragTool == null) return null;
        return new com.learnthink.core.agent.tools.callbacks.DelegatingToolCallback(ragTool, java.util.Map.of("course_id", ctx.courseId()));
    }
    @Override protected String getSelfReviewSystemPrompt(String t) { return promptLoader.get("agent/self_review_code"); }
    @Override protected boolean shouldRetrieveMore(GenerationTask t, List<ResourceGenerationState.SourceItem> s) { return s.size() < 2; }
    @Override protected String getGenerationSystemPrompt(GenerationTask task, List<ResourceGenerationState.SourceItem> sources) {
        return getGenPrompt(task);
    }

    private String getGenPrompt(GenerationTask task) {
        return promptLoader.get("generator/code")
            .replace("{personalization_note}", task.personalizationNote() != null ? task.personalizationNote() : "")
            .replace("{difficulty}", task.difficulty());
    }
    private String getGenPrompt(ResourceGenerationState.ResourcePlanItem item) {
        return promptLoader.get("generator/code")
            .replace("{personalization_note}", item.personalizationNote())
            .replace("{difficulty}", item.difficulty());
    }
    private String buildUserMsg(GenerationTask task, List<ResourceGenerationState.SourceItem> sources) {
        String st = sources.stream().map(s -> String.format("[%s] %s%s",
            s.bookTitle() != null ? "《" + s.bookTitle() + "》" : s.docId(),
            s.quote(), s.locator() != null ? " — " + s.locator() : ""))
            .collect(Collectors.joining("\n"));
        return String.format("Topic: %s\nKey points: %s\nReference sources:\n%s",
            task.title(), String.join(", ", task.keyPoints()), st);
    }

    private String validateAndNormalize(String raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            if (!root.has("files") || !root.has("steps")) {
                throw new RuntimeException("Missing required fields: files or steps");
            }
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            String extracted = extractJsonFromMarkdown(raw);
            if (extracted != null) return extracted;
            throw new RuntimeException("Invalid code content JSON: " + e.getMessage());
        }
    }

    private String extractJsonFromMarkdown(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                String candidate = raw.substring(start, end + 1);
                JsonNode root = mapper.readTree(candidate);
                if (root.has("files") && root.has("steps")) {
                    return mapper.writeValueAsString(root);
                }
            } catch (Exception ignored) {}
        }
        return null;
    }
}
