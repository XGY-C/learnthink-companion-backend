package com.learnthink.core.agent.impl.generators;

import com.learnthink.core.agent.orchestration.ResourceGenerationState;
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
public class DocumentGenerator implements TypeGenerator {

    private static final Logger log = LoggerFactory.getLogger(DocumentGenerator.class);
    private final ChatClient chatClient;
    private final PromptLoader promptLoader;

    public DocumentGenerator(@Qualifier("generationChatClientBuilder") ChatClient.Builder chatClientBuilder,
                             PromptLoader promptLoader) {
        this.chatClient = chatClientBuilder.build();
        this.promptLoader = promptLoader;
    }

    @Override
    public String type() { return "doc"; }

    @Override
    public ResourceGenerationState.GeneratedContent generate(
        ResourceGenerationState.ResourcePlanItem item,
        List<ResourceGenerationState.SourceItem> sources,
        ResourceGenerationState.ProfileSummary profile,
        boolean forceLowConfidence,
        String reviewFeedback) {

        String sourcesText = sources.stream()
            .map(s -> String.format("[doc:%s] %s — %s", s.docId(), s.quote(), s.locator()))
            .collect(Collectors.joining("\n"));

        String systemPrompt = promptLoader.get("generator/document")
            .replace("{difficulty}", item.difficulty())
            .replace("{personalization_note}", item.personalizationNote());

        if (reviewFeedback != null) {
            systemPrompt += "\n\nIMPORTANT: Previous version was rejected. Fix these issues: " + reviewFeedback;
        }

        String userMsg = String.format("""
            Topic: %s
            Title: %s
            Key points to cover: %s
            Sources:\n%s
            """,
            item.title(), item.title(),
            String.join(", ", item.keyPoints()),
            sourcesText.isEmpty() ? "(no sources available — use general knowledge with disclaimers)" : sourcesText);

        String content = chatClient.prompt()
            .messages(new SystemMessage(systemPrompt), new UserMessage(userMsg))
            .call()
            .content();

        if (forceLowConfidence) {
            content = "> ⚠️ Low confidence: evidence for this topic is limited. Content may be incomplete.\n\n" + content;
        }

        return new ResourceGenerationState.GeneratedContent(
            item.title(), content, "text/markdown", sources,
            forceLowConfidence ? "low" : "medium",
            Map.of("generator", "DocumentGenerator", "sourceCount", sources.size())
        );
    }
}
