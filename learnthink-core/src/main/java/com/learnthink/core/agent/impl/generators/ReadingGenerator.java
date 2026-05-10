package com.learnthink.core.agent.impl.generators;

import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ReadingGenerator implements TypeGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReadingGenerator.class);
    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = """
        You are a reading list curator for LearnThink Companion.
        Create an extended reading guide based on the topic and student profile.

        ## Output format (Markdown):
        ### Recommended Readings
        For each reading:
        - **Title**: ...
        - **Source/Author**: ...
        - **Why read**: 1-2 sentences connecting to the topic and student interests
        - **Estimated time**: X minutes

        ### Further Exploration
        - Topics, papers, or resources for deeper study

        ## Rules
        - Include 3-5 readings
        - Mix textbook references, papers, and online resources
        - Personalization: {personalization_note}
        """;

    public ReadingGenerator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String type() { return "reading"; }

    @Override
    public ResourceGenerationState.GeneratedContent generate(
        ResourceGenerationState.ResourcePlanItem item,
        List<ResourceGenerationState.SourceItem> sources,
        ResourceGenerationState.ProfileSummary profile,
        boolean forceLowConfidence,
        String reviewFeedback) {

        String prompt = SYSTEM_PROMPT
            .replace("{personalization_note}", item.personalizationNote());

        if (reviewFeedback != null) {
            prompt += "\n\nCORRECTION: " + reviewFeedback;
        }

        String content = chatClient.prompt()
            .messages(new SystemMessage(prompt),
                new UserMessage(String.format("Topic: %s\nKey points: %s\nStudent interests: %s",
                    item.title(), String.join(", ", item.keyPoints()),
                    String.join(", ", profile.style()))))
            .call()
            .content();

        return new ResourceGenerationState.GeneratedContent(
            item.title(), content, "text/markdown", sources,
            "medium",
            Map.of("generator", "ReadingGenerator", "sourceCount", sources.size())
        );
    }
}
