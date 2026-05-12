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
public class CodeGenerator implements TypeGenerator {

    private static final Logger log = LoggerFactory.getLogger(CodeGenerator.class);
    private final ChatClient chatClient;
    private final PromptLoader promptLoader;

    public CodeGenerator(@Qualifier("generationChatClientBuilder") ChatClient.Builder chatClientBuilder,
                         PromptLoader promptLoader) {
        this.chatClient = chatClientBuilder.build();
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
        String reviewFeedback) {

        String sourcesText = sources.stream()
            .map(s -> String.format("[%s] %s", s.docId(), s.quote()))
            .collect(Collectors.joining("\n"));

        String systemPrompt = promptLoader.get("generator/code")
            .replace("{personalization_note}", item.personalizationNote());

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
}
