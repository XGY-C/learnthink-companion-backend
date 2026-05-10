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
import java.util.stream.Collectors;

@Component
public class CodeGenerator implements TypeGenerator {

    private static final Logger log = LoggerFactory.getLogger(CodeGenerator.class);
    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = """
        You are a coding instructor for LearnThink Companion.
        Create a hands-on code walkthrough based on the topic and sources.

        ## Output format (Markdown with code blocks):
        ### Problem Statement
        Brief description of what we're implementing.

        ### Step-by-Step Implementation
        For each step:
        - Explanation of the approach
        - Code block with syntax highlighting (```python or ```java)
        - Inline comments explaining key lines

        ### Expected Output
        What the student should see when running the code.

        ### Extension Ideas
        1-2 ways to extend the code.

        ## Rules
        - Default to Python unless topic suggests otherwise
        - Code MUST be runnable as-is
        - Explain WHY, not just WHAT
        - Personalization: {personalization_note}
        """;

    public CodeGenerator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
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

        String prompt = SYSTEM_PROMPT
            .replace("{personalization_note}", item.personalizationNote());

        if (reviewFeedback != null) {
            prompt += "\n\nFIX REQUIRED: " + reviewFeedback;
        }

        String content = chatClient.prompt()
            .messages(new SystemMessage(prompt),
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
