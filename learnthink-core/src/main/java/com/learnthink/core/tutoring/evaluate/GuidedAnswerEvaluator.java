package com.learnthink.core.tutoring.evaluate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.tutoring.domain.EvaluationResult;
import com.learnthink.core.tutoring.domain.GuidedDialogueHistory;
import com.learnthink.core.tutoring.domain.GuidedStep;
import com.learnthink.core.tutoring.domain.GuidedStepState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.util.Objects;

@Component
public class GuidedAnswerEvaluator {
    private static final Logger log = LoggerFactory.getLogger(GuidedAnswerEvaluator.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public GuidedAnswerEvaluator(@Qualifier("chatChatClientBuilder") ChatClient.Builder builder,
                                  ObjectMapper objectMapper) {
        this.chatClient = builder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * 评估学生作答（调用点③）。
     * 使用 chatChatClientBuilder（快速、无温度需求）。
     */
    public EvaluationResult evaluate(GuidedStep step, String question, String studentAnswer,
                                     GuidedStepState state, GuidedDialogueHistory history) {
        String originalQuestion = history != null ? history.originalQuestion() : "";
        String previousEvaluations = formatPreviousEvaluations(history);
        int attemptCount = state != null ? state.attemptCount() : 0;

        String systemPrompt = """
            你是一位严谨但鼓励性的教师，正在评估学生在引导式教学中的回答。

            ## 原始问题
            %s

            ## 当前引导阶段
            %s: %s

            ## 你提出的问题
            %s

            ## 预期答案要点
            %s

            ## 学生的回答
            %s

            ## 评估上下文
            - 当前尝试次数：%d（第1次尝试可适度宽松，第2次应更明确指出问题）
            - 之前步骤表现：%s

            ## 评估要求
            1. 判断学生回答是否正确（核心思路对即可，不要求措辞完全一致）
            2. 如果部分正确，指出对的部分和不足
            3. 如果错误，指出具体问题，但不直接给出答案
            4. 反馈语气：鼓励性，根据之前步骤表现调整（连续答错时更鼓励，连续答对时增加挑战性）
            5. 反馈中**禁止给出完整答案**，只能给方向性提示

            输出 JSON（字段名必须使用英文，JSON 语法必须严格合法）:
            {"correct": true/false, "score": 0-100, "feedback": "反馈文本", "evaluation": "correct/incorrect/partial"}
            """.formatted(
                originalQuestion,
                step.stage(), step.title(),
                question,
                step.expectedAnswer(),
                studentAnswer,
                attemptCount,
                previousEvaluations
            );

        try {
            String response = chatClient.prompt()
                .system(systemPrompt)
                .user("评估")
                .call()
                .content();

            return parseEvaluationResult(response);
        } catch (Exception e) {
            log.error("Evaluation LLM call failed: {}", e.getMessage(), e);
            return new EvaluationResult(false, 0,
                "评估服务暂时不可用，请重试。" + e.getMessage(), "incorrect");
        }
    }

    private EvaluationResult parseEvaluationResult(String response) {
        if (response == null || response.isBlank()) {
            return new EvaluationResult(false, 0, "无法评估空回答", "incorrect");
        }

        // Extract JSON block from response
        String json = extractJson(response);
        if (json == null) {
            log.warn("No JSON found in evaluation response: {}", response);
            return new EvaluationResult(false, 0, "评估结果解析失败", "incorrect");
        }

        try {
            var node = objectMapper.readTree(json);
            boolean correct = node.path("correct").asBoolean(false);
            int score = node.path("score").asInt(0);
            String feedback = node.path("feedback").asText("");
            String evaluation = node.path("evaluation").asText("incorrect");
            return new EvaluationResult(correct, score, feedback, evaluation);
        } catch (Exception e) {
            log.warn("Failed to parse evaluation JSON: {}", e.getMessage());
            return new EvaluationResult(false, 0, "评估结果解析失败", "incorrect");
        }
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        if (start < 0) return null;
        int braceCount = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '{') braceCount++;
            else if (c == '}') {
                braceCount--;
                if (braceCount == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private String formatPreviousEvaluations(GuidedDialogueHistory history) {
        if (history == null || history.turns() == null || history.turns().isEmpty()) {
            return "无（这是第一步）";
        }
        StringBuilder sb = new StringBuilder();
        for (var turn : history.turns()) {
            sb.append("步骤").append(turn.stage()).append(": ")
              .append(turn.evaluation()).append("（尝试").append(turn.attempts()).append("次）; ");
        }
        return sb.toString();
    }
}
