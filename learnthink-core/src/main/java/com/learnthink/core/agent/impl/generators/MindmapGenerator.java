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
public class MindmapGenerator implements TypeGenerator {

    private static final Logger log = LoggerFactory.getLogger(MindmapGenerator.class);
    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = """
        You are a knowledge visualization expert for LearnThink Companion.
        Create a concept mindmap from the topic outline.

        ## Output format (strict JSON — compatible with mindmap renderers):
        {
          "root": {
            "text": "Topic Name",
            "children": [
              {
                "text": "Subtopic 1",
                "children": [
                  { "text": "Key concept A" },
                  { "text": "Key concept B" }
                ]
              },
              {
                "text": "Subtopic 2",
                "children": [
                  { "text": "Key concept C" },
                  { "text": "Key concept D" }
                ]
              }
            ]
          }
        }

        ## Rules
        - Root node = main topic
        - Level 1 children = major subtopics (3-5 nodes)
        - Level 2 children = key concepts under each subtopic (2-4 nodes each)
        - Max depth: 3 levels
        - Each node text should be ≤20 characters
        - Structure should follow the topic outline provided
        """;

    public MindmapGenerator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
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

        String prompt = SYSTEM_PROMPT;
        if (reviewFeedback != null) {
            prompt += "\n\nCORRECTION: " + reviewFeedback;
        }

        String content = chatClient.prompt()
            .messages(new SystemMessage(prompt),
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
