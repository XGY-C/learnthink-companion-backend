package com.learnthink.core.tutoring.phase;

import com.learnthink.core.tutoring.domain.*;
import com.learnthink.core.tutoring.event.TutoringEventEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import java.util.Map;

@Component
public class Phase3Generator {
    private static final Logger log = LoggerFactory.getLogger(Phase3Generator.class);

    private final ChatClient chatClient;
    private final GeneratorPromptBuilder promptBuilder;
    private final StreamingHandler streamingHandler;

    public Phase3Generator(@Qualifier("generationChatClientBuilder") ChatClient.Builder builder,
                            GeneratorPromptBuilder promptBuilder,
                            StreamingHandler streamingHandler) {
        this.chatClient = builder.build();
        this.promptBuilder = promptBuilder;
        this.streamingHandler = streamingHandler;
    }

    public Map<String, String> generate(ExecutionPlan plan, ResolvedResources resources,
                          TutoringContext context, TutoringEventEmitter emitter) {
        Map<String, Object> profile = context.profileSnapshot();

        String systemPrompt = promptBuilder.buildSystemPrompt(
            plan, resources, profile, context.question());

        log.info("Phase 3 starting generation for plan {}, {} sections, {} resource groups",
            plan.planId(),
            plan.sectionBlueprints() != null ? plan.sectionBlueprints().size() : 0,
            resources != null ? resources.resources().size() : 0);

        Flux<org.springframework.ai.chat.model.ChatResponse> stream = chatClient.prompt()
            .system(systemPrompt)
            .user("请按照解答蓝图生成完整解答。")
            .stream()
            .chatResponse();

        return streamingHandler.handleStream(stream, emitter, plan);
    }
}
