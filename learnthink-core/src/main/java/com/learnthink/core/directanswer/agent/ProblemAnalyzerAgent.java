package com.learnthink.core.directanswer.agent;

import com.learnthink.core.directanswer.domain.response.AnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase1: ProblemAnalyzerAgent — 分析题型与学科。
 * 输入：question + courseContext
 * 输出：AnalysisResult（problemType, subject, tags, answerFirst）
 * 模型：非推理模型（deepseek-chat），1 次调用
 */
@Component
public class ProblemAnalyzerAgent {
    private static final Logger log = LoggerFactory.getLogger(ProblemAnalyzerAgent.class);

    private static final String SYSTEM_PROMPT = """
        你是一位经验丰富的数学老师。请分析以下题目：
        1. 题目类型：求解、证明、还是探索？
        2. 学科领域：如代数、几何、概率统计、微积分等
        3. 关键标签：提取3-5个核心知识点标签
        4. 是否适合先給答案：如果题目是简单计算或查公式型，置 answerFirst=true

        请严格按照 JSON 格式输出，不要输出其他内容：
        {
          "problemType": "求解|证明|探索",
          "subject": "学科名称",
          "tags": ["标签1", "标签2", "标签3"],
          "answerFirst": true/false
        }""";

    private final ChatClient chatClient;

    public ProblemAnalyzerAgent(@Qualifier("chatChatClientBuilder") ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 分析题目。
     */
    public AnalysisResult analyze(String question, String courseId) {
        log.info("ProblemAnalyzerAgent analyzing: question='{}'", truncate(question, 50));
        try {
            String response = chatClient.prompt()
                .messages(new SystemMessage(SYSTEM_PROMPT), new UserMessage(question))
                .call()
                .content();
            return parseResponse(response, question);
        } catch (Exception e) {
            log.warn("ProblemAnalyzerAgent LLM call failed, using fallback: {}", e.getMessage());
            return fallbackAnalysis(question);
        }
    }

    @SuppressWarnings("unchecked")
    private AnalysisResult parseResponse(String response, String question) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var map = mapper.readValue(extractJson(response), java.util.Map.class);
            String problemType = (String) map.getOrDefault("problemType", "求解");
            String subject = (String) map.getOrDefault("subject", "数学");
            List<String> tags = (List<String>) map.getOrDefault("tags", List.of());
            boolean answerFirst = (boolean) map.getOrDefault("answerFirst", false);
            return new AnalysisResult(problemType, subject, tags, answerFirst);
        } catch (Exception e) {
            log.warn("Failed to parse ProblemAnalyzerAgent response: {}", e.getMessage());
            return fallbackAnalysis(question);
        }
    }

    private AnalysisResult fallbackAnalysis(String question) {
        return new AnalysisResult("求解", "数学", List.of("计算", "公式"), true);
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
    }
}
