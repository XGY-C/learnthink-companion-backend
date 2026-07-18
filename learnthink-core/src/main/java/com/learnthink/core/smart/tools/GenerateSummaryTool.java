package com.learnthink.core.smart.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentTool;
import com.learnthink.core.smart.domain.ConceptBreakdown;
import com.learnthink.core.smart.domain.ConceptStatus;
import com.learnthink.core.smart.domain.SmartContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 生成阶段总结。
 * <p>收敛时调用，总结学生学到的概念和掌握情况。
 * 调用后通过 {@code ctxRef} 更新上下文的 converged 和 convergenceSummary。</p>
 */
public class GenerateSummaryTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(GenerateSummaryTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient chatClient;
    private final AtomicReference<SmartContext> ctxRef;

    public GenerateSummaryTool(ChatClient chatClient, AtomicReference<SmartContext> ctxRef) {
        this.chatClient = chatClient;
        this.ctxRef = ctxRef;
    }

    @Override
    public String name() {
        return "generate_summary";
    }

    @Override
    public String description() {
        return "生成学习阶段总结。在所有概念掌握并通过迁移检验后调用。"
             + "总结学生学到的概念和掌握情况，给出学习建议。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {},
              "additionalProperties": false
            }""";
    }

    @Override
    public String execute(String jsonArgs) {
        try {
            SmartContext ctx = ctxRef.get();
            if (ctx == null) {
                return "{\"error\":\"上下文不存在\"}";
            }

            log.info("GenerateSummaryTool: generating summary for session {}", ctx.sessionId());

            // 构建概念掌握情况
            StringBuilder conceptStatus = new StringBuilder();
            if (ctx.concepts() != null) {
                for (ConceptBreakdown c : ctx.concepts()) {
                    ConceptStatus s = ctx.conceptStatus() != null
                        ? ctx.conceptStatus().getOrDefault(c.id(), ConceptStatus.UNVERIFIED)
                        : ConceptStatus.UNVERIFIED;
                    conceptStatus.append("- ").append(c.label())
                        .append(": ").append(s.label());
                    if (c.description() != null && !c.description().isBlank()) {
                        conceptStatus.append(" (").append(c.description()).append(")");
                    }
                    conceptStatus.append("\n");
                }
            }

            // 构建交互历史摘要
            StringBuilder turnHistory = new StringBuilder();
            if (ctx.turns() != null) {
                for (var t : ctx.turns()) {
                    turnHistory.append(t.toLine()).append("\n");
                }
            }

            String systemPrompt = """
                你是一个学习总结生成器。请根据学生的学习情况，生成一份简洁的学习总结。
                要求：
                1. 总结学生掌握了哪些概念
                2. 给出简短的学习建议
                3. 语气积极、鼓励
                4. 用中文
                """;

            String userRequest = """
                概念掌握情况：
                %s

                交互历史：
                %s

                总轮数：%d

                请生成学习总结。
                返回 JSON：
                {
                  "summary": "总结文本",
                  "masteredConcepts": ["已掌握的概念列表"],
                  "suggestions": ["学习建议"]
                }
                """.formatted(conceptStatus, turnHistory, ctx.totalTurns());

            String response = chatClient.prompt()
                .system(systemPrompt)
                .user(userRequest)
                .call()
                .content();

            if (response == null || response.isBlank()) {
                return "{\"error\":\"生成失败\"}";
            }

            // 解析 LLM 返回的 JSON，提取 summary
            String cleanJson = extractJson(response);
            String summary = "";
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = MAPPER.readValue(cleanJson, Map.class);
                summary = String.valueOf(parsed.getOrDefault("summary", ""));
            } catch (Exception e) {
                log.warn("Failed to parse summary JSON, using raw response: {}", e.getMessage());
                summary = cleanJson;
            }

            // 更新上下文：标记收敛 + 保存总结
            ctxRef.set(ctx.withConverged(summary));
            log.info("GenerateSummaryTool: context updated, converged=true, summary length={}",
                summary.length());

            return cleanJson;

        } catch (Exception e) {
            log.error("GenerateSummaryTool failed", e);
            return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    /**
     * 从 LLM 响应中提取 JSON（去除 markdown 代码块包裹）。
     */
    private String extractJson(String response) {
        String trimmed = response.trim();
        // 去除 markdown 代码块 ```json ... ``` 或 ``` ... ```
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
