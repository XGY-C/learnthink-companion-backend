package com.learnthink.core.agent.impl.generators;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class MindmapGenerator extends AutonomousGenerator implements TypeGenerator {

    private final PromptLoader promptLoader;

    public MindmapGenerator(
            @Qualifier("generationChatClientBuilder") ChatClient.Builder chatClientBuilder,
            RagTool ragTool, PromptLoader promptLoader) {
        super(chatClientBuilder.defaultOptions(OpenAiChatOptions.builder().temperature(0.3).build()).build(),
            List.of(), ragTool);
        this.promptLoader = promptLoader;
    }

    @Override public String type() { return "mindmap"; }
    @Override public boolean requiresSourceCoverage() { return false; }

    @Override
    public ResourceGenerationState.GeneratedContent generate(
            ResourceGenerationState.ResourcePlanItem item,
            List<ResourceGenerationState.SourceItem> typeSources,
            ResourceGenerationState.ProfileSummary profile,
            boolean forceLowConfidence, String reviewFeedback, AgentContext ctx) {

        GenerationTask task = new GenerationTask("MindmapGenerator", "mindmap", item.title(), item.title(),
            item.keyPoints(), item.difficulty(), item.personalizationNote(),
            List.of(), List.of(), reviewFeedback, typeSources);

        GenerationResult result = runAutonomousLoop(task, ctx);
        if (result.content() == null) throw new RuntimeException("Mindmap generation failed");

        return new ResourceGenerationState.GeneratedContent(item.title(), cleanJsonOutput(result.content()),
            "application/json", result.sources(), "medium",
            Map.of("generator", "MindmapGenerator", "autonomous", true, "confidence", result.confidence()));
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
        content = cleanJsonOutput(content);
        log.info("[AI-RESPONSE][MindmapGenerator] revise length={} chars\n{}",
            content != null ? content.length() : 0,
            content != null ? content.substring(0, Math.min(2000, content.length())) : "null");
        return new ResourceGenerationState.GeneratedContent(item.title(), content, "application/json", typeSources,
            "medium", Map.of("generator", "MindmapGenerator", "revised", true));
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
    @Override protected String getSelfReviewSystemPrompt(String t) { return promptLoader.get("agent/self_review_mindmap"); }
    @Override protected boolean shouldRetrieveMore(GenerationTask t, List<ResourceGenerationState.SourceItem> s) { return false; }
    @Override protected String getGenerationSystemPrompt(GenerationTask task, List<ResourceGenerationState.SourceItem> sources) {
        return getGenPrompt(task);
    }

    private String getGenPrompt(GenerationTask task) {
        return promptLoader.get("generator/mindmap")
            .replace("{difficulty}", task.difficulty())
            .replace("{personalization_note}", task.personalizationNote() != null ? task.personalizationNote() : "");
    }
    private String getGenPrompt(ResourceGenerationState.ResourcePlanItem item) {
        return promptLoader.get("generator/mindmap")
            .replace("{difficulty}", item.difficulty())
            .replace("{personalization_note}", item.personalizationNote());
    }
    private String buildUserMsg(GenerationTask task, List<ResourceGenerationState.SourceItem> sources) {
        String st = sources.stream().map(s -> String.format("[%s] %s - %s",
            (s.bookTitle() != null ? "《" + s.bookTitle() + "》" : "") + (s.chapterTitle() != null ? s.chapterTitle() : ""),
            s.quote(), s.locator())).collect(Collectors.joining("\n"));
        return String.format("Topic: %s\nKey points: %s\nDifficulty: %s\nSources:\n%s",
            task.title(), String.join(", ", task.keyPoints()), task.difficulty(),
            st.isEmpty() ? "(no sources - use RAG tool to search)" : st);
    }

    /**
     * 清洗 LLM 输出，提取纯 JSON 文本。
     * 逐个匹配 ``` 代码块并尝试解析，正确处理多代码块场景。
     */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final Pattern FENCE_BLOCK = Pattern.compile("```(?:\\w+)?\\s*([\\s\\S]*?)\\s*```");

    private String cleanJsonOutput(String content) {
        if (content == null || content.isBlank()) return content;
        String text = content.trim();

        // 已经是合法 JSON
        if (isValidJson(text)) return text;

        // 逐个尝试 ``` 代码块
        Matcher matcher = FENCE_BLOCK.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1).trim();
            if (isValidJson(candidate)) return candidate;
        }

        // 兜底：首个 { 到末尾 }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private boolean isValidJson(String json) {
        try {
            JSON_MAPPER.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
