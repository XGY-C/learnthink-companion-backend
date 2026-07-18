package com.learnthink.core.agent.tools.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向用户提问工具
 * <p>当 AI 需要澄清信息以继续推进时，通过此工具向用户提问。
 * 工具执行时会返回一个 pause 信号，调用方（如 SSE 流）应暂停当前轮次，
 * 将问题推送到前端，等待用户回复后恢复。</p>
 *
 * <h3>使用场景</h3>
 * <ul>
 *   <li>画像对话中信息不足，需要向学生确认学习偏好</li>
 *   <li>解题引导中需要确认学生的理解程度</li>
 *   <li>学习路径规划中需要确认学生的目标</li>
 * </ul>
 *
 * <h3>返回格式</h3>
 * <p>返回 JSON 中包含 {@code pauseForUser} 字段，调用方应检测此字段：</p>
 * <pre>{@code
 * {
 *   "questions": [
 *     {
 *       "id": "q1",
 *       "prompt": "你的学习目标是什么？",
 *       "type": "text",          // text | choice
 *       "options": ["考试冲刺", "日常学习", "兴趣拓展"]  // type=choice 时使用
 *     }
 *   ]
 * }
 * }</pre>
 */
public class AskUserTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(AskUserTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "ask_user";
    }

    @Override
    public String description() {
        return "向用户提问以澄清信息。当缺失的信息确实阻碍了合理推进时调用此工具，" +
               "在单次调用中提出所有需要澄清的问题。否则基于合理假设继续推进，" +
               "并在回答中说明所做的假设。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "questions": {
                  "type": "array",
                  "description": "要向用户提出的问题列表",
                  "items": {
                    "type": "object",
                    "properties": {
                      "id": {
                        "type": "string",
                        "description": "问题唯一标识（如 q1, q2）"
                      },
                      "prompt": {
                        "type": "string",
                        "description": "问题文本"
                      },
                      "type": {
                        "type": "string",
                        "description": "问题类型",
                        "enum": ["text", "choice"]
                      },
                      "options": {
                        "type": "array",
                        "description": "选项列表（type 为 choice 时使用）",
                        "items": {"type": "string"}
                      }
                    },
                    "required": ["id", "prompt"]
                  }
                }
              },
              "required": ["questions"],
              "additionalProperties": false
            }""";
    }

    @Override
    public String execute(String jsonArgs) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = MAPPER.readValue(jsonArgs, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> questions = (List<Map<String, Object>>) args.getOrDefault("questions", List.of());

            if (questions.isEmpty()) {
                return errorJson("问题列表不能为空");
            }

            log.info("AskUserTool: {} question(s)", questions.size());

            // 构造暂停载荷——调用方应检测此字段，暂停轮次并推送问题到前端
            Map<String, Object> pausePayload = new LinkedHashMap<>();
            pausePayload.put("questions", questions);
            pausePayload.put("action", "ask_user");

            // 工具返回内容（也作为暂停期间的占位文本）
            String summary = formatQuestions(questions);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", summary);
            result.put("pauseForUser", pausePayload);
            result.put("success", true);
            return MAPPER.writeValueAsString(result);

        } catch (Exception e) {
            log.error("AskUserTool failed", e);
            return errorJson("提问失败: " + e.getMessage());
        }
    }

    /** 格式化问题摘要（用于工具返回文本） */
    private String formatQuestions(List<Map<String, Object>> questions) {
        StringBuilder sb = new StringBuilder("已向用户提出以下问题：\n");
        for (int i = 0; i < questions.size(); i++) {
            Map<String, Object> q = questions.get(i);
            sb.append(i + 1).append(". ")
              .append(q.getOrDefault("prompt", ""))
              .append("\n");
        }
        sb.append("\n等待用户回复后继续...");
        return sb.toString();
    }

    private String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message, "success", false));
        } catch (Exception e) {
            return "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        }
    }
}
