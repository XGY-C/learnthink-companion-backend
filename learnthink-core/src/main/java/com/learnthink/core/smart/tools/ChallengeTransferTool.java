package com.learnthink.core.smart.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentTool;
import com.learnthink.core.smart.domain.ConceptBreakdown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

/**
 * 出变形题检验迁移能力。
 * <p>当所有概念已掌握时调用，基于已掌握概念生成变形题，
 * 检验学生能否将所学应用到新情境。</p>
 */
public class ChallengeTransferTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ChallengeTransferTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient chatClient;
    private final List<ConceptBreakdown> concepts;

    public ChallengeTransferTool(ChatClient chatClient, List<ConceptBreakdown> concepts) {
        this.chatClient = chatClient;
        this.concepts = concepts;
    }

    @Override
    public String name() {
        return "challenge_transfer";
    }

    @Override
    public String description() {
        return "出变形题检验迁移能力。当所有概念已掌握时调用。"
             + "基于已掌握概念生成变形题，考察学生能否将所学应用到新情境。"
             + "传入变形方向描述，返回变形题目。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "variation": {
                  "type": "string",
                  "description": "变形方向描述（如：换一组数据、改变条件、应用到新场景）"
                }
              },
              "required": ["variation"],
              "additionalProperties": false
            }""";
    }

    @Override
    public String execute(String jsonArgs) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = MAPPER.readValue(jsonArgs, Map.class);
            String variation = String.valueOf(args.getOrDefault("variation", "")).trim();

            if (variation.isEmpty()) {
                variation = "换一组新数据，考察迁移能力";
            }

            log.info("ChallengeTransferTool: variation='{}'", variation);

            // 构建概念列表描述
            StringBuilder conceptList = new StringBuilder();
            for (ConceptBreakdown c : concepts) {
                conceptList.append("- ").append(c.label());
                if (c.description() != null && !c.description().isBlank()) {
                    conceptList.append(": ").append(c.description());
                }
                conceptList.append("\n");
            }

            String systemPrompt = """
                你是一个出题专家。请基于学生已掌握的概念，出一道变形迁移题。
                要求：
                1. 题目要考察学生能否将已学概念应用到新情境
                2. 题目难度略高于原始概念，但不超过学生能力范围
                3. 返回 JSON 格式，包含题目和参考答案
                """;

            String userRequest = """
                已掌握的概念：
                %s

                变形方向：%s

                请出一道变形迁移题。
                返回 JSON：
                {
                  "question": "题目内容",
                  "expectedAnswer": "参考答案",
                  "transferFocus": "这道题考察的迁移能力点"
                }
                """.formatted(conceptList, variation);

            String response = chatClient.prompt()
                .system(systemPrompt)
                .user(userRequest)
                .call()
                .content();

            return response != null ? extractJson(response) : "{\"error\":\"生成失败\"}";

        } catch (Exception e) {
            log.error("ChallengeTransferTool failed", e);
            return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    /**
     * 从 LLM 响应中提取 JSON（去除 markdown 代码块包裹）。
     */
    private String extractJson(String response) {
        String trimmed = response.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (start > 0 && end > start) {
                return trimmed.substring(start + 1, end).trim();
            }
        }
        return trimmed;
    }
}
