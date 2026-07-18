package com.learnthink.core.agent.tools.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 写入用户画像工具
 * <p>记录学生在对话中展现的行为信号、学习偏好和薄弱点信息。
 * 这些信号会在会话结束时由 ProfileService 的 LLM 流水线处理，更新画像版本。</p>
 *
 * <h3>信号类型</h3>
 * <ul>
 *   <li>{@code learning_style} — 学习风格偏好（如视觉型、听觉型）</li>
 *   <li>{@code weakness} — 薄弱知识点</li>
 *   <li>{@code preference} — 学习偏好（如喜欢先看例子再学理论）</li>
 *   <li>{@code behavior} — 行为特征（如经常跳过练习）</li>
 * </ul>
 */
public class WriteProfileTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(WriteProfileTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "write_profile";
    }

    @Override
    public String description() {
        return "记录学生在对话中展现的行为信号、学习偏好或薄弱点。" +
               "这些信号会在会话结束时由画像分析流水线处理。" +
               "user_id 和 course_id 由系统自动注入，无需提供。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "signal_type": {
                  "type": "string",
                  "description": "信号类型",
                  "enum": ["learning_style", "weakness", "preference", "behavior"]
                },
                "content": {
                  "type": "string",
                  "description": "信号内容描述（如'学生对递归理解有困难'）"
                },
                "confidence": {
                  "type": "number",
                  "description": "置信度（0.0-1.0），默认 0.7",
                  "default": 0.7
                }
              },
              "required": ["signal_type", "content"],
              "additionalProperties": false
            }""";
    }

    @Override
    public String execute(String jsonArgs) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = MAPPER.readValue(jsonArgs, Map.class);
            String userId = String.valueOf(args.getOrDefault("user_id", ""));
            String courseId = String.valueOf(args.getOrDefault("course_id", ""));
            String signalType = String.valueOf(args.getOrDefault("signal_type", "")).trim();
            String content = String.valueOf(args.getOrDefault("content", "")).trim();
            double confidence = args.containsKey("confidence")
                    ? ((Number) args.get("confidence")).doubleValue()
                    : 0.7;

            if (signalType.isEmpty() || content.isEmpty()) {
                return errorJson("signal_type 和 content 不能为空");
            }

            log.info("WriteProfileTool: userId={}, courseId={}, type={}, content='{}'",
                    userId, courseId, signalType, content);

            // ================================================================
            // 信号记录——当前实现仅日志记录
            // 接入真实 ProfileSignalService 后，替换为实际调用：
            //
            //   profileSignalService.recordSignal(userId, courseId, signalType, content, confidence);
            //
            // 或使用 ProfileBehaviorAccumulatorService 累积行为信号
            // ================================================================

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("message", "画像信号已记录");
            result.put("signal_type", signalType);
            result.put("content", content);
            result.put("confidence", confidence);
            return MAPPER.writeValueAsString(result);

        } catch (Exception e) {
            log.error("WriteProfileTool failed", e);
            return errorJson("写入画像失败: " + e.getMessage());
        }
    }

    private String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message, "success", false));
        } catch (Exception e) {
            return "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        }
    }
}
