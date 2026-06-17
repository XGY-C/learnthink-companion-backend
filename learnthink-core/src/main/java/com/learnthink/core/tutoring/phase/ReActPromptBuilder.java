package com.learnthink.core.tutoring.phase;

import com.learnthink.core.config.PromptLoader;
import com.learnthink.core.tutoring.adaptation.ProfileAdapter;
import com.learnthink.core.tutoring.domain.ReactState;
import com.learnthink.core.tutoring.domain.TutoringContext;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ReActPromptBuilder {
    private final PromptLoader promptLoader;
    private final ProfileAdapter profileAdapter;

    public ReActPromptBuilder(PromptLoader promptLoader, ProfileAdapter profileAdapter) {
        this.promptLoader = promptLoader;
        this.profileAdapter = profileAdapter;
    }

    public String buildSystemPrompt(TutoringContext context) {
        String base = promptLoader.get("tutoring/architect-system");

        Map<String, Object> profile = context.profileSnapshot();
        String strategyTendency = profileAdapter.computeStrategyTendency(profile);
        String contentAssociations = profileAdapter.computeContentAssociations(
            context.recentLearning(), context.recentTutoring());
        String styleParams = profileAdapter.computeStyleParams(profile);

        String adaptiveInjection = strategyTendency + "\n" + contentAssociations + "\n" + styleParams;

        return base
            .replace("{question}", Objects.toString(context.question(), ""))
            .replace("{profile.knowledgeBaseSummary}",
                Objects.toString(profile != null ? profile.getOrDefault("knowledgeBaseSummary", "") : "", ""))
            .replace("{profile.cognitiveStyle}",
                Objects.toString(profile != null ? profile.getOrDefault("cognitiveStyle", "textual") : "textual"))
            .replace("{profile.weakPoints}", formatList(profile != null ? profile.get("weakPoints") : null))
            .replace("{profile.learningPace}",
                Objects.toString(profile != null ? profile.getOrDefault("learningPace", "moderate") : "moderate"))
            .replace("{pathPosition}", formatPathPosition(context.pathPosition()))
            .replace("{recentLearning}", formatRecentLearning(context.recentLearning()))
            .replace("{recentTutoring}", formatRecentTutoring(context.recentTutoring()))
            .replace("{自适应注入：三层画像指令}", adaptiveInjection);
    }

    public List<Message> buildMessages(TutoringContext context, ReactState reactState) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt(context)));

        if (reactState != null && reactState.conversationHistory() != null) {
            // Continue from saved ReAct state
            StringBuilder historyBuilder = new StringBuilder();
            historyBuilder.append("## 历史对话\n\n");
            for (var entry : reactState.conversationHistory()) {
                String role = Objects.toString(entry.get("role"), "");
                if ("architect".equals(role)) {
                    historyBuilder.append("Architect: Thought: ")
                        .append(Objects.toString(entry.get("thought"), "")).append("\n");
                    historyBuilder.append("Action: ").append(Objects.toString(entry.get("action"), "")).append("\n");
                } else if ("student".equals(role)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> response = (Map<String, Object>) entry.get("response");
                    if (response != null) {
                        historyBuilder.append("Student: ");
                        if (Boolean.TRUE.equals(response.get("skipped"))) {
                            historyBuilder.append("跳过澄清\n");
                        } else if (response.get("selectedOptionId") != null) {
                            historyBuilder.append("选择了选项 ").append(response.get("selectedOptionId")).append("\n");
                        } else if (response.get("freeInput") != null) {
                            historyBuilder.append(response.get("freeInput")).append("\n");
                        }
                    }
                }
            }
            historyBuilder.append("\n当前问题（已拼接澄清结果）：").append(reactState.accumulatedQuestion());
            messages.add(new UserMessage(historyBuilder.toString()));
        } else {
            messages.add(new UserMessage(context.question()));
        }

        return messages;
    }

    @SuppressWarnings("unchecked")
    private String formatList(Object obj) {
        if (obj instanceof List<?> list) {
            return String.join("、", list.stream().map(Object::toString).toList());
        }
        return Objects.toString(obj, "");
    }

    private String formatPathPosition(Map<String, Object> path) {
        if (path == null || path.isEmpty()) return "未知";
        return Objects.toString(path.getOrDefault("chapterTitle", ""), "") + " - " +
            Objects.toString(path.getOrDefault("sectionTitle", ""), "");
    }

    private String formatRecentLearning(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        for (var item : items) {
            sb.append("- ").append(Objects.toString(item.get("title"), "")).append("\n");
        }
        return sb.toString();
    }

    private String formatRecentTutoring(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        for (var item : items) {
            sb.append("- ").append(Objects.toString(item.get("question"), "")).append("\n");
        }
        return sb.toString();
    }
}
