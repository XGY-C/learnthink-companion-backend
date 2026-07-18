package com.learnthink.core.directanswer.prompt;

import com.learnthink.core.directanswer.domain.response.AnalysisResult;
import com.learnthink.core.directanswer.domain.response.SectionBlueprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DirectAnswer System Prompt 构建器。
 */
@Component
public class DirectAnswerPromptBuilder {
    private static final Logger log = LoggerFactory.getLogger(DirectAnswerPromptBuilder.class);

    /**
     * 构建 Phase1 用户 prompt。
     */
    public String buildPhase1UserPrompt(String question, String courseContext) {
        return String.format("题目：%s\n课程：%s", question, courseContext != null ? courseContext : "通用");
    }

    /**
     * 构建 Phase2 用户 prompt。
     */
    public String buildPhase2UserPrompt(AnalysisResult analysis, String question) {
        return String.format(
            "题目：%s\n题型：%s\n学科：%s\n标签：%s\n请规划 7 段讲解结构。",
            question, analysis.problemType(), analysis.subject(),
            String.join(", ", analysis.tags()));
    }

    /**
     * 构建 Phase3 用户 prompt。
     */
    public String buildPhase3UserPrompt(AnalysisResult analysis,
                                         List<SectionBlueprint> blueprints,
                                         String question,
                                         String ragContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("题目：").append(question).append("\n");
        sb.append("题型：").append(analysis.problemType()).append("\n");
        sb.append("学科：").append(analysis.subject()).append("\n\n");
        sb.append("请按以下结构生成解答：\n");
        for (SectionBlueprint bp : blueprints) {
            sb.append("- ").append(bp.id()).append(": ").append(bp.title()).append("\n");
        }
        if (ragContext != null && !ragContext.isBlank()) {
            sb.append("\n参考资料：\n").append(ragContext);
        }
        return sb.toString();
    }
}
