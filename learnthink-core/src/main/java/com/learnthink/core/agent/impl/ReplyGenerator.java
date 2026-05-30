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
 * Plan-then-Generate 架构 — 第二阶段：回复生成器
 * <p>基于 {@link ConversationPlanner} 的结构化计划生成最终对外显示的回复。
 * 无工具调用——纯文本生成，使用适中的温度参数以获得自然输出。</p>
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
     * 基于规划器输出流式生成回复
     * <p>无工具调用——纯文本生成。</p>
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
