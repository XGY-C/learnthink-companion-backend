package com.learnthink.core.agent.impl.generators;

import com.learnthink.core.agent.framework.AgentContext;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import org.springframework.ai.openai.OpenAiChatOptions;
import com.learnthink.core.config.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class CodeGenerator implements TypeGenerator {

    private static final Logger log = LoggerFactory.getLogger(CodeGenerator.class);
    private final ChatClient chatClient;
    private final PromptLoader promptLoader;

    public CodeGenerator(@Qualifier("generationChatClientBuilder") ChatClient.Builder chatClientBuilder,
                         PromptLoader promptLoader) {
        this.chatClient = chatClientBuilder.defaultOptions(OpenAiChatOptions.builder().temperature(0.2).build()).build();
        this.promptLoader = promptLoader;
    }

    @Override
    public String type() { return "code"; }

    @Override
    public ResourceGenerationState.GeneratedContent generate(
        ResourceGenerationState.ResourcePlanItem item,
        List<ResourceGenerationState.SourceItem> sources,
        ResourceGenerationState.ProfileSummary profile,
        boolean forceLowConfidence,
        String reviewFeedback,
        AgentContext context) {

        String sourcesText = sources.stream()
            .map(s -> {
                String book = s.bookTitle() != null && !s.bookTitle().isBlank() ? "《" + s.bookTitle() + "》" : "";
                String chapter = s.chapterTitle() != null && !s.chapterTitle().isBlank() ? s.chapterTitle() : "";
                String tag = book + chapter;
                String ref = tag.isBlank() ? s.docId() : tag;
                String loc = s.locator() != null && !s.locator().isBlank() ? " — " + s.locator() : "";
                return String.format("[%s] %s%s", ref, s.quote(), loc);
            })
            .collect(Collectors.joining("\n"));

        String systemPrompt = promptLoader.get("generator/code")
            .replace("{personalization_note}", item.personalizationNote())
            .replace("{difficulty}", item.difficulty());

        if (reviewFeedback != null) {
            systemPrompt += "\n\nFIX REQUIRED: " + reviewFeedback;
        }

        String content = chatClient.prompt()
            .messages(new SystemMessage(systemPrompt),
                new UserMessage(String.format("Topic: %s\nKey points: %s\nReference sources:\n%s",
                    item.title(), String.join(", ", item.keyPoints()), sourcesText)))
            .call()
            .content();

        return new ResourceGenerationState.GeneratedContent(
            item.title(), content, "text/markdown", sources,
            forceLowConfidence ? "low" : "high",
            Map.of("generator", "CodeGenerator", "sourceCount", sources.size())
        );
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

        String systemPrompt = promptLoader.get("generator/code")
            .replace("{personalization_note}", item.personalizationNote())
            .replace("{difficulty}", item.difficulty());
        systemPrompt += "\n\n## 修改要求\n" + reviewFeedback;

        String userMsg = "需修改的代码：\n" + (original.content() != null ? original.content().substring(0, Math.min(2000, original.content().length())) : "");

        String content = chatClient.prompt()
            .messages(new SystemMessage(systemPrompt), new UserMessage(userMsg))
            .call()
            .content();

        return new ResourceGenerationState.GeneratedContent(
            item.title(), content, "text/markdown", sources,
            forceLowConfidence ? "low" : "high",
            Map.of("generator", "CodeGenerator", "revised", true)
        );
    }
}
