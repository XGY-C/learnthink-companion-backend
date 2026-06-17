package com.learnthink.core.tutoring.adaptation;

import com.learnthink.core.tutoring.domain.TutoringContext;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class ProfileAdapter {

    public String computeStrategyTendency(Map<String, Object> profileSnapshot) {
        if (profileSnapshot == null || profileSnapshot.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("基于以下画像事实，调整你的教学规划倾向（这些是倾向，不是规则，你仍需根据具体问题做判断）：\n\n");

        Object cognitiveStyle = profileSnapshot.get("cognitiveStyle");
        if (cognitiveStyle != null) {
            sb.append("- 认知风格：").append(cognitiveStyle)
                .append("（visual → 倾向图解优先；textual → 倾向层级清晰的文本结构）\n");
        }

        Object learningPace = profileSnapshot.get("learningPace");
        if (learningPace != null) {
            sb.append("- 学习节奏：").append(learningPace)
                .append("（slow → 倾向类比引入、每段一个概念；fast → 倾向高信息密度、直奔主题）\n");
        }

        Object weakPoints = profileSnapshot.get("weakPoints");
        if (weakPoints != null) {
            sb.append("- 薄弱点：").append(formatList(weakPoints))
                .append("（如果与当前问题相关，考虑加入前置回顾 section）\n");
        }

        Object knowledgeBaseSummary = profileSnapshot.get("knowledgeBaseSummary");
        if (knowledgeBaseSummary != null && !knowledgeBaseSummary.toString().isBlank()) {
            sb.append("- 前置知识水平：").append(knowledgeBaseSummary).append("\n");
        }

        return sb.toString();
    }

    public String computeContentAssociations(List<Map<String, Object>> recentLearning,
                                              List<Map<String, Object>> recentTutoring) {
        StringBuilder sb = new StringBuilder();

        if (recentLearning != null && !recentLearning.isEmpty()) {
            String titles = recentLearning.stream()
                .map(m -> Objects.toString(m.get("title"), ""))
                .filter(s -> !((String)s).isBlank())
                .collect(Collectors.joining("、"));
            if (!titles.isBlank()) {
                sb.append("学生最近学过：").append(titles).append("\n");
                sb.append("在规划解答时，如果与当前问题相关，在 generationHint 中提示 Generator 关联这些内容，建立新旧知识的连接。\n\n");
            }
        }

        if (recentTutoring != null && !recentTutoring.isEmpty()) {
            String questions = recentTutoring.stream()
                .map(m -> Objects.toString(m.get("question"), ""))
                .filter(s -> !((String)s).isBlank())
                .limit(2)
                .collect(Collectors.joining("；"));
            if (!questions.isBlank()) {
                sb.append("学生最近答疑记录：").append(questions).append("\n");
                sb.append("如果当前问题与之前答疑有延续关系，考虑以'延续上次讨论'的方式开头。\n");
            }
        }

        return sb.toString();
    }

    public String computeStyleParams(Map<String, Object> profileSnapshot) {
        if (profileSnapshot == null || profileSnapshot.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("教学风格参数（供参考，非强制）：\n");

        Object cognitiveStyle = profileSnapshot.get("cognitiveStyle");
        String formality = "neutral";
        String sentenceLength = "varied";
        boolean useMetaphor = true;
        String mathRigor = "moderate";
        String voice = "encouraging";

        if ("visual".equals(cognitiveStyle)) {
            useMetaphor = true;
        } else if ("textual".equals(cognitiveStyle)) {
            useMetaphor = false;
            mathRigor = "rigorous";
        }

        Object learningPace = profileSnapshot.get("learningPace");
        if ("slow".equals(learningPace)) {
            sentenceLength = "short";
            voice = "encouraging";
        } else if ("fast".equals(learningPace)) {
            sentenceLength = "substantial";
            voice = "direct";
            formality = "formal";
        }

        sb.append("- 语体正式度：").append(formality).append("（casual / neutral / formal）\n");
        sb.append("- 句子长度倾向：").append(sentenceLength).append("（short / varied / substantial）\n");
        sb.append("- 类比使用倾向：").append(useMetaphor).append("\n");
        sb.append("- 数学严谨度：").append(mathRigor).append("（intuitive / moderate / rigorous）\n");
        sb.append("- 语气：").append(voice).append("（encouraging / direct / academic）\n");

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String formatList(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream().map(Object::toString).collect(Collectors.joining("、"));
        }
        return Objects.toString(obj, "");
    }
}
