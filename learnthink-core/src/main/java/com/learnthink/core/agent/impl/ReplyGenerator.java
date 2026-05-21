package com.learnthink.core.agent.impl;

import com.learnthink.core.config.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Plan-then-Generate architecture — Phase 2: ReplyGenerator.
 *
 * <p>Generates the final visible reply based on a structured plan from
 * {@link ConversationPlanner}. No tool calls — pure text generation with
 * moderate temperature for natural output.</p>
 */
@Component
public class ReplyGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReplyGenerator.class);

    private final ChatClient chatClient;
    private final PromptLoader promptLoader;

    public ReplyGenerator(
            @Qualifier("generationChatClientBuilder") ChatClient.Builder chatClientBuilder,
            PromptLoader promptLoader) {
        this.chatClient = chatClientBuilder.build();
        this.promptLoader = promptLoader;
    }

    /**
     * Stream the generated reply based on the planner's output.
     * No tool calls — pure text generation.
     */
    public Flux<ChatResponse> streamReply(GenInput input) {
        String prompt = promptLoader.get("agent/generator_chat")
                .replace("{course_context}", input.courseContext())
                .replace("{profile_context}", input.profileContext())
                .replace("{plan_result}", input.planResult());

        long start = System.currentTimeMillis();
        log.info("ReplyGenerator: starting generation (plan length: {} chars)", input.planResult().length());

        return chatClient.prompt()
                .messages(new SystemMessage(prompt), new UserMessage(input.lastUserMessage()))
                .stream().chatResponse()
                .doFinally(signalType -> {
                    long elapsed = System.currentTimeMillis() - start;
                    log.info("ReplyGenerator: stream complete ({}ms)", elapsed);
                });
    }

    public record GenInput(
            String courseContext,
            String profileContext,
            String planResult,
            String lastUserMessage
    ) {}
}
