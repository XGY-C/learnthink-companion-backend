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
public class ExerciseGenerator implements TypeGenerator {

    private static final Logger log = LoggerFactory.getLogger(ExerciseGenerator.class);
    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = """
        You are an expert quiz designer for LearnThink Companion.
        Create practice exercises based on provided sources.

        ## Output format (strict JSON array):
        [
          {
            "type": "multiple_choice|true_false|short_answer|fill_blank",
            "question": "Question text",
            "options": ["A. option1", "B. option2", "C. option3", "D. option4"],
            "answer": "Correct answer",
            "explanation": "Why this is correct, referencing sources",
            "difficulty": "easy|medium|hard",
            "sourceRef": "doc_id"
          }
        ]

        ## Rules
        - Generate 5-8 questions covering the key points
        - Mix question types: at least 2 multiple_choice, 1 short_answer
        - Every question MUST reference a source (sourceRef field)
        - Difficulty distribution: 30% easy, 50% medium, 20% hard
        - Personalization: {personalization_note}
        """;

    public ExerciseGenerator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String type() { return "exercise"; }

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

        String prompt = SYSTEM_PROMPT
            .replace("{personalization_note}", item.personalizationNote());

        if (reviewFeedback != null) {
            prompt += "\n\nCORRECTION REQUIRED: " + reviewFeedback;
        }

        String content = chatClient.prompt()
            .messages(new SystemMessage(prompt),
                new UserMessage(String.format("Topic: %s\nKey points: %s\nSources:\n%s",
                    item.title(), String.join(", ", item.keyPoints()), sourcesText)))
            .call()
            .content();

        return new ResourceGenerationState.GeneratedContent(
            item.title(), content, "application/json", sources,
            forceLowConfidence ? "low" : "high",
            Map.of("generator", "ExerciseGenerator", "sourceCount", sources.size())
        );
    }
}
