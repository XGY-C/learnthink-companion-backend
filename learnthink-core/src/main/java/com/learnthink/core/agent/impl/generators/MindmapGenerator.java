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

@Component
public class MindmapGenerator implements TypeGenerator {

    private static final Logger log = LoggerFactory.getLogger(MindmapGenerator.class);
    private final ChatClient chatClient;
    private final PromptLoader promptLoader;

    public MindmapGenerator(@Qualifier("generationChatClientBuilder") ChatClient.Builder chatClientBuilder,
                            PromptLoader promptLoader) {
        this.chatClient = chatClientBuilder.build();
        this.promptLoader = promptLoader;
    }

    @Override
    public String type() { return "mindmap"; }

    @Override
    public ResourceGenerationState.GeneratedContent generate(
        ResourceGenerationState.ResourcePlanItem item,
        List<ResourceGenerationState.SourceItem> sources,
        ResourceGenerationState.ProfileSummary profile,
        boolean forceLowConfidence,
        String reviewFeedback) {

        String systemPrompt = promptLoader.get("generator/mindmap");
        if (reviewFeedback != null) {
            systemPrompt += "\n\nCORRECTION: " + reviewFeedback;
        }

        String content = chatClient.prompt()
            .messages(new SystemMessage(systemPrompt),
                new UserMessage(String.format("Topic: %s\nKey points: %s\nDifficulty: %s",
                    item.title(), String.join(", ", item.keyPoints()), item.difficulty())))
            .call()
            .content();

        return new ResourceGenerationState.GeneratedContent(
            item.title(), content, "application/json", sources,
            "medium",
            Map.of("generator", "MindmapGenerator")
        );
    }
}
