package com.learnthink.core.agent.tools.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 深度推理工具
 * <p>对复杂子问题进行独立的 LLM 推理调用。当当前上下文不足以给出确切答案时使用。</p>
 *
 * <p>与主对话 LLM 分离，使用独立的 ChatClient 调用，避免干扰主对话的上下文窗口。</p>
 */
public class ReasonTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ReasonTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient chatClient;

    public ReasonTool(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String name() {
        return "reason";
    }

    @Override
    public String description() {
        return "对复杂子问题进行深度推理，使用独立的 LLM 调用。" +
               "当当前上下文不足以给出确切答案时调用此工具。" +
               "传入需要推理的问题和可选的上下文，返回推理过程和结论。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "需要深度推理的子问题"
                },
                "context": {
                  "type": "string",
                  "description": "支持推理的上下文信息（可选）"
                }
              },
              "required": ["query"],
              "additionalProperties": false
            }""";
    }

    @Override
    public String execute(String jsonArgs) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = MAPPER.readValue(jsonArgs, Map.class);
            String query = String.valueOf(args.getOrDefault("query", "")).trim();
            String context = args.containsKey("context") ? String.valueOf(args.get("context")) : "";

            if (query.isEmpty()) {
                return errorJson("推理问题不能为空");
            }

            log.info("ReasonTool: query='{}', contextLen={}", query, context.length());

            String systemPrompt = """
                你是一个深度推理助手。请对给定的问题进行仔细的、分步骤的推理分析。
                先梳理已知信息和约束条件，然后逐步推导，最后给出明确的结论。
                用清晰、结构化的中文回答。""";

            String userRequest = context != null && !context.isBlank()
                    ? "上下文：\n" + context + "\n\n问题：" + query
                    : "问题：" + query;

            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userRequest)
                    .call()
                    .content();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("query", query);
            result.put("reasoning", response != null ? response : "");
            result.put("success", response != null && !response.isBlank());
            return MAPPER.writeValueAsString(result);

        } catch (Exception e) {
            log.error("ReasonTool failed", e);
            return errorJson("推理失败: " + e.getMessage());
        }
    }

    private String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message, "reasoning", "", "success", false));
        } catch (Exception e) {
            return "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        }
    }
}
