package com.learnthink.core.agent.tools.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentTool;
import com.learnthink.core.service.ProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读取用户画像工具
 * <p>读取学生的学习风格、薄弱知识点、学习偏好等画像信息。
 * 复用现有 {@link ProfileService}。</p>
 *
 * <p>调用方（如 ConversationAgent）通过服务端注入参数提供 user_id 和 course_id，
 * LLM 无需也无法指定这些参数。</p>
 */
public class ReadProfileTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ReadProfileTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProfileService profileService;

    public ReadProfileTool(ProfileService profileService) {
        this.profileService = profileService;
    }

    @Override
    public String name() {
        return "read_profile";
    }

    @Override
    public String description() {
        return "读取学生画像信息，包括学习风格、薄弱知识点、学习偏好等。" +
               "当需要了解学生的个性化学习特征以提供针对性指导时调用此工具。" +
               "user_id 和 course_id 由系统自动注入，无需提供。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {},
              "required": [],
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

            if (userId.isEmpty() || courseId.isEmpty()) {
                return errorJson("缺少 user_id 或 course_id（需由服务端注入）");
            }

            log.info("ReadProfileTool: userId={}, courseId={}", userId, courseId);

            Map<String, Object> profile = profileService.getProfile(userId, courseId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("userId", userId);
            result.put("courseId", courseId);
            result.put("profile", profile);
            result.put("success", profile != null && !profile.isEmpty());
            return MAPPER.writeValueAsString(result);

        } catch (Exception e) {
            log.error("ReadProfileTool failed", e);
            return errorJson("读取画像失败: " + e.getMessage());
        }
    }

    private String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message, "profile", Map.of(), "success", false));
        } catch (Exception e) {
            return "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        }
    }
}
