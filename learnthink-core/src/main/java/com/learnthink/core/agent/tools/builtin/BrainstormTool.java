package com.learnthink.core.agent.tools.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 头脑风暴工具
 * <p>广泛探索主题的多种可能性，为每个方向给出简要理由。</p>
 *
 * <p>使用独立的 LLM 调用，适合在规划学习路径、生成学习方案等场景中
 * 提供多角度的发散思考。</p>
 */
public class BrainstormTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(BrainstormTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient chatClient;

    public BrainstormTool(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String name() {
        return "brainstorm";
    }

    @Override
    public String description() {
        return "广泛探索主题的多种可能性，为每个方向给出简要理由。" +
               "适用于学习路径规划、学习方案设计等需要发散思考的场景。" +
               "返回多个候选方向及其理由。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "topic": {
                  "type": "string",
                  "description": "要头脑风暴的主题、目标或问题"
                },
                "context": {
                  "type": "string",
                  "description": "可选的支持性上下文、约束条件或背景信息"
                },
                "count": {
                  "type": "integer",
                  "description": "返回方向数量（3-10），默认 5",
                  "default": 5
                }
              },
              "required": ["topic"],
              "additionalProperties": false
            }""";
    }

    @Override
    public String execute(String jsonArgs) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = MAPPER.readValue(jsonArgs, Map.class);
            String topic = String.valueOf(args.getOrDefault("topic", "")).trim();
            String context = args.containsKey("context") ? String.valueOf(args.get("context")) : "";
            int count = args.containsKey("count")
                    ? Math.min(Math.max(((Number) args.get("count")).intValue(), 3), 10)
                    : 5;

            if (topic.isEmpty()) {
                return errorJson("主题不能为空");
            }

            log.info("BrainstormTool: topic='{}', count={}", topic, count);

            String systemPrompt = """
                你是一个创意头脑风暴助手。请针对给定主题，提供 %d 个不同方向的可能性，
                每个方向用 1-2 句话简要说明理由。确保方向之间有差异化，覆盖不同角度。
                用结构化的中文回答，每个方向用编号列出。""".formatted(count);

            StringBuilder userRequest = new StringBuilder();
            userRequest.append("主题：").append(topic);
            if (context != null && !context.isBlank()) {
                userRequest.append("\n上下文：").append(context);
            }
            userRequest.append("\n\n请提供 ").append(count).append(" 个方向。");

            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userRequest.toString())
                    .call()
                    .content();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("topic", topic);
            result.put("ideas", response != null ? response : "");
            result.put("count", count);
            result.put("success", response != null && !response.isBlank());
            return MAPPER.writeValueAsString(result);

        } catch (Exception e) {
            log.error("BrainstormTool failed", e);
            return errorJson("头脑风暴失败: " + e.getMessage());
        }
    }

    private String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message, "ideas", "", "success", false));
        } catch (Exception e) {
            return "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        }
    }
}
