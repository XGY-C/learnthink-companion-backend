package com.learnthink.core.directanswer.agent;

import com.learnthink.core.directanswer.domain.response.AnalysisResult;
import com.learnthink.core.directanswer.domain.response.SectionBlueprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase2: AnswerPlannerAgent — 规划 7 段结构。
 * 输入：AnalysisResult + question
 * 输出：List&lt;SectionBlueprint&gt;（7 段蓝图）
 * 模型：非推理模型（deepseek-chat），1 次调用
 */
@Component
public class AnswerPlannerAgent {
    private static final Logger log = LoggerFactory.getLogger(AnswerPlannerAgent.class);

    private static final List<SectionBlueprint> DEFAULT_BLUEPRINTS = List.of(
        new SectionBlueprint("answer_hero", "答案", 0),
        new SectionBlueprint("problem_analysis", "题目分析", 1),
        new SectionBlueprint("strategy_overview", "解题策略", 2),
        new SectionBlueprint("reasoning_chain", "推理过程", 3),
        new SectionBlueprint("method_summary", "方法总结", 4),
        new SectionBlueprint("error_warning", "易错提醒", 5),
        new SectionBlueprint("prerequisite_knowledge", "前置知识", 6)
    );

    private static final String SYSTEM_PROMPT = """
        你是一位教学经验丰富的数学老师。请根据题目分析结果，规划 7 段讲解结构。
        只需要输出 JSON 格式的 7 个段落标题，允许根据题目类型调整标题但要保持 7 段结构。
        格式如下：
        {
          "sections": [
            {"id": "answer_hero", "title": "答案"},
            {"id": "problem_analysis", "title": "题目分析"},
            {"id": "strategy_overview", "title": "解题策略"},
            {"id": "reasoning_chain", "title": "推理过程"},
            {"id": "method_summary", "title": "方法总结"},
            {"id": "error_warning", "title": "易错提醒"},
            {"id": "prerequisite_knowledge", "title": "前置知识"}
          ]
        }""";

    private final ChatClient chatClient;

    public AnswerPlannerAgent(@Qualifier("chatChatClientBuilder") ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 规划 7 段结构。
     */
    public List<SectionBlueprint> plan(AnalysisResult analysis, String question) {
        log.info("AnswerPlannerAgent planning sections for: {}", truncate(question, 50));
        try {
            String userPrompt = String.format(
                "题目：%s\n题型：%s\n学科：%s\n标签：%s\n请规划 7 段讲解结构。",
                question, analysis.problemType(), analysis.subject(),
                String.join(", ", analysis.tags()));

            String response = chatClient.prompt()
                .messages(new SystemMessage(SYSTEM_PROMPT), new UserMessage(userPrompt))
                .call()
                .content();
            return parseResponse(response);
        } catch (Exception e) {
            log.warn("AnswerPlannerAgent LLM call failed, using defaults: {}", e.getMessage());
            return DEFAULT_BLUEPRINTS;
        }
    }

    @SuppressWarnings("unchecked")
    private List<SectionBlueprint> parseResponse(String response) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String json = extractJson(response);
            var map = mapper.readValue(json, java.util.Map.class);
            List<Map<String, Object>> sections = (List<Map<String, Object>>) map.get("sections");
            if (sections == null || sections.isEmpty()) return DEFAULT_BLUEPRINTS;

            List<SectionBlueprint> blueprints = new ArrayList<>();
            int order = 0;
            for (Map<String, Object> s : sections) {
                String id = (String) s.getOrDefault("id", "section_" + order);
                String title = (String) s.getOrDefault("title", "段落 " + (order + 1));
                blueprints.add(new SectionBlueprint(id, title, order++));
            }
            return blueprints;
        } catch (Exception e) {
            log.warn("Failed to parse AnswerPlannerAgent response: {}", e.getMessage());
            return DEFAULT_BLUEPRINTS;
        }
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return text;
    }

    private String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
    }
}
