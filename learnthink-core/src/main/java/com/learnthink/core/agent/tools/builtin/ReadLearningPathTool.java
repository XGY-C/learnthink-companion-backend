package com.learnthink.core.agent.tools.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取学习路径工具
 * <p>查询学生当前的学习路径进度，包括已完成节点、当前节点和待完成节点。
 * 用于 AI 在规划学习建议时了解学生的进度上下文。</p>
 *
 * <h3>接入说明</h3>
 * <p>当前为占位实现。接入真实数据源时，替换 {@link #queryLearningPath} 方法，
 * 从 LearningPath / LearningPathVersion 表中查询数据。</p>
 */
public class ReadLearningPathTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ReadLearningPathTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "read_learning_path";
    }

    @Override
    public String description() {
        return "查询学生当前的学习路径进度，包括已完成的知识点、当前学习节点和待完成节点。" +
               "当需要基于学生进度提供针对性学习建议时调用此工具。" +
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

            log.info("ReadLearningPathTool: userId={}, courseId={}", userId, courseId);

            // ================================================================
            // 占位实现：返回模拟数据
            // 接入真实数据源时替换 queryLearningPath 方法：
            //
            //   LearningPath path = learningPathMapper.selectLatest(userId, courseId);
            //   List<LearningPathVersion> versions = ...
            // ================================================================
            Map<String, Object> pathData = queryLearningPath(userId, courseId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("userId", userId);
            result.put("courseId", courseId);
            result.put("learningPath", pathData);
            result.put("success", true);
            return MAPPER.writeValueAsString(result);

        } catch (Exception e) {
            log.error("ReadLearningPathTool failed", e);
            return errorJson("读取学习路径失败: " + e.getMessage());
        }
    }

    /**
     * 查询学习路径（占位实现）
     * <p>接入真实数据源时替换此方法，从 LearningPath / LearningPathVersion 表查询。</p>
     */
    private Map<String, Object> queryLearningPath(String userId, String courseId) {
        // 占位数据——接入真实数据源后删除
        return Map.of(
                "status", "placeholder",
                "message", "学习路径数据为占位实现。接入 LearningPath Mapper 后将返回真实进度。",
                "completedNodes", List.of(),
                "currentNode", "",
                "pendingNodes", List.of(),
                "progress", 0.0
        );
    }

    private String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "error", message,
                    "learningPath", Map.of(),
                    "success", false
            ));
        } catch (Exception e) {
            return "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        }
    }
}
