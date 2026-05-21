package com.learnthink.core.agent.impl;

import com.learnthink.core.agent.framework.AgentContext;
import com.learnthink.core.config.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Plan-then-Generate architecture — Phase 1: ConversationPlanner.
 *
 * <p>Analyzes user intent and plans the reply structure.
 * All output goes to the thinking chain ({@code agent.thought} SSE events),
 * consumed by {@code ReplyGenerator} which produces the visible reply.</p>
 *
 * <p>Key difference from {@link ConversationAgent}: this agent's output is
 * <b>never</b> shown as the visible reply. It is a "thinker" that feeds
 * structured plans to the generator.</p>
 */
@Component
public class ConversationPlanner {

    private static final Logger log = LoggerFactory.getLogger(ConversationPlanner.class);

    private final ChatClient chatClient;
    private final PromptLoader promptLoader;

    public ConversationPlanner(
            @Qualifier("chatChatClientBuilder") ChatClient.Builder chatClientBuilder,
            PromptLoader promptLoader) {
        this.chatClient = chatClientBuilder.build();
        this.promptLoader = promptLoader;
    }

    /**
     * Stream the planner's analysis and plan.
     * The caller (ChatServiceImpl) is responsible for parsing the
     * {@code [ANALYSIS]}, {@code [PLAN]}, and {@code ---PLAN_END---} markers
     * from the streaming text and emitting appropriate SSE events.
     */
    public Flux<ChatResponse> streamPlan(ConversationInput input, AgentContext ctx) {
        String systemPrompt = input.systemPromptOverride() != null && !input.systemPromptOverride().isBlank()
                ? input.systemPromptOverride()
                : promptLoader.get("agent/planner_chat");
        List<Message> messages = buildMessages(systemPrompt, input.conversationHistory());

        long start = System.currentTimeMillis();
        log.info("ConversationPlanner: starting plan for round {}", input.roundNumber());

        var promptSpec = chatClient.prompt().messages(messages);
        ToolCallback ragToolCallback = ctx.get("rag_tool");
        if (ragToolCallback != null) {
            promptSpec = promptSpec.toolCallbacks(ragToolCallback);
        }
        return promptSpec.stream().chatResponse()
                .doFinally(signalType -> {
                    long elapsed = System.currentTimeMillis() - start;
                    log.info("ConversationPlanner: stream complete ({}ms)", elapsed);
                });
    }

    private List<Message> buildMessages(String systemPrompt, List<Map<String, String>> history) {
        List<Message> messages = new java.util.ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        for (var msg : history) {
            String role = msg.getOrDefault("role", "user");
            String content = msg.getOrDefault("content", "");
            if ("user".equals(role)) {
                messages.add(new UserMessage(content));
            } else if ("assistant".equals(role)) {
                messages.add(new AssistantMessage(content));
            }
        }
        return messages;
    }

    public record ConversationInput(
            String courseId,
            List<Map<String, String>> conversationHistory,
            int roundNumber,
            String systemPromptOverride
    ) {}
}
