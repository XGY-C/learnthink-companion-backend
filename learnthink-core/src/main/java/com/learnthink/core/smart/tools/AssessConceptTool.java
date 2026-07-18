package com.learnthink.core.smart.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentTool;
import com.learnthink.core.smart.domain.ConceptBreakdown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出题探测概念掌握度。
 * <p>LLM 调用此工具，针对特定概念出一道快速判断/选择题。
 * 学生回答后，LLM 判断是否正确并调用 {@code update_concept_status} 更新状态。</p>
 */
public class AssessConceptTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(AssessConceptTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient chatClient;
    private final List<ConceptBreakdown> concepts;

    public AssessConceptTool(ChatClient chatClient, List<ConceptBreakdown> concepts) {
        this.chatClient = chatClient;
        this.concepts = concepts;
    }

    @Override
    public String name() {
        return "assess_concept";
    }

    @Override
    public String description() {
        return "针对特定子概念出一道快速判断/选择题，用于探测学生是否掌握。"
             + "传入概念ID和难度，返回一道选择题。"
             + "学生回答后，LLM 判断是否正确并调用 update_concept_status 更新状态。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "conceptId": {
                  "type": "string",
                  "description": "要探测的概念ID"
                },
                "questionType": {
                  "type": "string",
                  "enum": ["true_false", "multiple_choice", "short_answer"],
                  "description": "题目类型"
                },
                "difficulty": {
                  "type": "string",
                  "enum": ["easy", "medium", "hard"]
                }
              },
              "required": ["conceptId"],
              "additionalProperties": false
            }""";
    }

    @Override
    public String execute(String jsonArgs) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = MAPPER.readValue(jsonArgs, Map.class);
            String conceptId = String.valueOf(args.getOrDefault("conceptId", "")).trim();
            String questionType = String.valueOf(args.getOrDefault("questionType", "multiple_choice"));
            String difficulty = String.valueOf(args.getOrDefault("difficulty", "medium"));

            if (conceptId.isEmpty()) {
                return errorJson("conceptId 不能为空");
            }

            // 查找概念
            ConceptBreakdown concept = concepts.stream()
                .filter(c -> conceptId.equals(c.id()))
                .findFirst()
                .orElse(null);

            if (concept == null) {
                return errorJson("概念不存在: " + conceptId);
            }

            log.info("AssessConceptTool: conceptId={}, type={}, difficulty={}",
                conceptId, questionType, difficulty);

            String systemPrompt = """
                你是一个出题专家。请根据给定的概念信息，出一道简洁的测试题。
                要求：
                1. 题目要准确考察该概念的核心理解
                2. 题目简洁明了，学生能快速作答
                3. 返回 JSON 格式，包含题目、选项（选择题时）、正确答案、解析
                """;

            String userRequest = """
                概念：%s
                描述：%s
                难度：%s
                题目类型：%s

                请出一道%s难度的%s题。
                返回 JSON：
                {
                  "question": "题目内容",
                  "options": {"A": "...", "B": "...", "C": "...", "D": "..."},  // 选择题时有此项
                  "correctAnswer": "A",
                  "explanation": "为什么选这个答案"
                }
                """.formatted(
                    concept.label(),
                    concept.description() != null ? concept.description() : "",
                    difficulty, questionType, difficulty, questionType
                );

            String response = chatClient.prompt()
                .system(systemPrompt)
                .user(userRequest)
                .call()
                .content();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("conceptId", conceptId);
            result.put("conceptLabel", concept.label());
            result.put("question", response != null ? extractJson(response) : "");
            result.put("success", response != null && !response.isBlank());
            return MAPPER.writeValueAsString(result);

        } catch (Exception e) {
            log.error("AssessConceptTool failed", e);
            return errorJson("出题失败: " + e.getMessage());
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

    private String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message, "success", false));
        } catch (Exception e) {
            return "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        }
    }
}
