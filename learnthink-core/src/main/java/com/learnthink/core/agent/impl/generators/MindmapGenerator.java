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

@Component
public class MindmapGenerator implements TypeGenerator {

    private static final Logger log = LoggerFactory.getLogger(MindmapGenerator.class);
    private final ChatClient chatClient;
    private final PromptLoader promptLoader;

    public MindmapGenerator(@Qualifier("generationChatClientBuilder") ChatClient.Builder chatClientBuilder,
                            PromptLoader promptLoader) {
        this.chatClient = chatClientBuilder.defaultOptions(OpenAiChatOptions.builder().temperature(0.3).build()).build();
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
        String reviewFeedback,
        AgentContext context) {

        String systemPrompt = promptLoader.get("generator/mindmap")
            .replace("{difficulty}", item.difficulty())
            .replace("{personalization_note}", item.personalizationNote());
        if (reviewFeedback != null) {
            systemPrompt += "\n\n修正要求：" + reviewFeedback;
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

    @Override
    public ResourceGenerationState.GeneratedContent revise(
        ResourceGenerationState.ResourcePlanItem item,
        List<ResourceGenerationState.SourceItem> sources,
        ResourceGenerationState.ProfileSummary profile,
        boolean forceLowConfidence,
        String reviewFeedback,
        ResourceGenerationState.GeneratedContent original,
        AgentContext context) {

        String systemPrompt = promptLoader.get("generator/mindmap")
            .replace("{difficulty}", item.difficulty())
            .replace("{personalization_note}", item.personalizationNote());
        systemPrompt += "\n\n## 修改要求\n" + reviewFeedback;

        String userMsg = "需修改的思维导图 JSON：\n" + (original.content() != null ? original.content().substring(0, Math.min(2000, original.content().length())) : "");

        String content = chatClient.prompt()
            .messages(new SystemMessage(systemPrompt), new UserMessage(userMsg))
            .call()
            .content();

        return new ResourceGenerationState.GeneratedContent(
            item.title(), content, "application/json", sources,
            "medium",
            Map.of("generator", "MindmapGenerator", "revised", true)
        );
    }
}
