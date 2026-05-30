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
 * Unified 对话模式 — 单次思维 + 工具调用 + 回复生成
 * <p>与 {@link ConversationPlanner} → {@link ReplyGenerator} 的两阶段流水线不同，
 * 此 Agent 仅需一次 LLM 调用，在单个流式过程中同时完成思考模式、工具访问
 * （{@code rag_retrieve}）和直接回复生成。</p>
 *
 * <p>LLM 使用其内部思维链（reasoning_content）进行分析和规划，
 * 然后将对外显示的回复作为常规内容输出。不使用 [ANALYSIS]/[PLAN] 标记——回复即最终文本。</p>
 *
 * <p>SSE 思考事件（RETRIEVE、RAG）由通过 {@link AgentContext} 注入的工具回调驱动。
 * 调用方负责流前事件（CONTEXT）和流后事件（REFLECT、DONE）。</p>
 */
@Component
public class UnifiedGenerator {

    private static final Logger log = LoggerFactory.getLogger(UnifiedGenerator.class);

    private final ChatClient chatClient;
    private final PromptLoader promptLoader;

    public UnifiedGenerator(
            @Qualifier("unifiedChatClientBuilder") ChatClient.Builder chatClientBuilder,
            PromptLoader promptLoader) {
        this.chatClient = chatClientBuilder.build();
        this.promptLoader = promptLoader;
    }

    /**
     * Stream the unified thinking + reply in a single pass.
     * Tool calls are handled transparently via callbacks in AgentContext.
     * The output text IS the final reply visible to the student.
     */
    public Flux<ChatResponse> streamUnified(UnifiedInput input, AgentContext ctx) {
        String systemPrompt = buildPrompt(input);

        List<Message> messages = new java.util.ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        for (var msg : input.conversationHistory()) {
            String role = msg.getOrDefault("role", "user");
            String content = msg.getOrDefault("content", "");
            if ("user".equals(role)) {
                messages.add(new UserMessage(content));
            } else if ("assistant".equals(role)) {
                messages.add(new AssistantMessage(content));
            }
        }

        long start = System.currentTimeMillis();
        log.info("UnifiedGenerator: starting single-pass for round {}", input.roundNumber());

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
                    log.info("UnifiedGenerator: stream complete ({}ms)", elapsed);
                });
    }

    private String buildPrompt(UnifiedInput input) {
        String modeHint = "";
        if ("resource".equals(input.chatMode())) {
            modeHint = "\n\n## 当前模式\n用户已切换至「资源生成」模式，请关注用户的资源需求。";
        } else if ("plan".equals(input.chatMode())) {
            modeHint = "\n\n## 当前模式\n用户已切换至「学习规划」模式，请关注用户的学习目标和规划需求。";
        }
        return promptLoader.get("agent/unified_chat")
                .replace("{course_context}", input.courseContext())
                .replace("{profile_context}", input.profileContext())
                + modeHint;
    }

    public record UnifiedInput(
            String courseContext,
            String profileContext,
            List<Map<String, String>> conversationHistory,
            int roundNumber,
            String chatMode
    ) {}
}
