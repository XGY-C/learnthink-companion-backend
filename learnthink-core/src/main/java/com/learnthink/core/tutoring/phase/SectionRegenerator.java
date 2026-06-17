package com.learnthink.core.tutoring.phase;

import com.learnthink.core.tutoring.domain.*;
import com.learnthink.core.tutoring.event.TutoringEventEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import java.util.List;

@Component
public class SectionRegenerator {
    private static final Logger log = LoggerFactory.getLogger(SectionRegenerator.class);

    private final ChatClient chatClient;
    private final GeneratorPromptBuilder promptBuilder;

    public SectionRegenerator(@Qualifier("generationChatClientBuilder") ChatClient.Builder builder,
                               GeneratorPromptBuilder promptBuilder) {
        this.chatClient = builder.build();
        this.promptBuilder = promptBuilder;
    }

    public void regenerate(String sessionId, String sectionId, String action,
                            String instruction, ExecutionPlan plan,
                            ResolvedResources resources,
                            String originalAnswer, TutoringEventEmitter emitter) {
        SectionBlueprint targetSection = null;
        if (plan.sectionBlueprints() != null) {
            targetSection = plan.sectionBlueprints().stream()
                .filter(s -> s.id().equals(sectionId))
                .findFirst().orElse(null);
        }

        if (targetSection == null) {
            emitter.error("SECTION_NOT_FOUND", "Section not found: " + sectionId, "3", false);
            return;
        }

        List<RetrievedChunk> sectionResources = targetSection.resourceRefs() != null
            ? targetSection.resourceRefs().stream()
                .flatMap(refId -> resources.getForRequirement(refId).stream())
                .toList()
            : List.of();

        String prompt = promptBuilder.buildRegeneratePrompt(
            originalAnswer, targetSection.title(), action, instruction,
            targetSection, sectionResources);

        Flux<String> stream = chatClient.prompt()
            .user(prompt)
            .stream()
            .content();

        StringBuilder content = new StringBuilder();
        stream.doOnNext(chunk -> content.append(chunk))
            .doOnComplete(() -> {
                emitter.sectionRegenerated(sectionId, content.toString());
                log.info("Section {} regenerated with action {}", sectionId, action);
            })
            .doOnError(error -> {
                log.error("Section regeneration failed for {}: {}", sectionId, error.getMessage());
                emitter.error("REGENERATE_FAILED", error.getMessage(), "3", true);
            })
            .subscribe();
    }
}
