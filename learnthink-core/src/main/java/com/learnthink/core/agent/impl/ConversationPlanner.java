package com.learnthink.core.agent.impl;

import com.learnthink.core.agent.runtime.AgentContext;
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
 * Plan-then-Generate 架构 — 第一阶段：对话规划器
 * <p>分析用户意图并规划回复结构。所有输出进入思考链（{@code agent.thought} SSE 事件），
 * 由 {@code ReplyGenerator} 消费后生成对外显示的回复。</p>
 *
 * <p>与 {@link ConversationAgent} 的关键区别：该 Agent 的输出<b>不会</b>直接显示为回复，
 * 它只是一个"思考者"，将结构化计划喂给生成器。</p>
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
     * 流式输出规划器的分析和计划
     * <p>调用方（ChatServiceImpl）负责从流式文本中解析
     * {@code [ANALYSIS]}、{@code [PLAN]} 和 {@code ---PLAN_END---} 标记，
     * 并发射相应的 SSE 事件。</p>
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
        ToolCallback bookInfoToolCallback = ctx.get("book_info_tool");
        if (bookInfoToolCallback != null) {
            promptSpec = promptSpec.toolCallbacks(bookInfoToolCallback);
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
