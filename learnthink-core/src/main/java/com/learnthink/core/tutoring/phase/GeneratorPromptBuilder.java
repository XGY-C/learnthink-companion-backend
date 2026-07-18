package com.learnthink.core.tutoring.phase;

import com.learnthink.core.config.PromptLoader;
import com.learnthink.core.tutoring.domain.*;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class GeneratorPromptBuilder {
    private final PromptLoader promptLoader;

    public GeneratorPromptBuilder(PromptLoader promptLoader) {
        this.promptLoader = promptLoader;
    }

    public String buildSystemPrompt(ExecutionPlan plan, ResolvedResources resources,
                                     Map<String, Object> profile, String question) {
        String base = promptLoader.get("tutoring/generator-system");

        return base
            .replace("{sectionBlueprints 的格式化文本，每个 section 包含 id, title, purpose, generationHint}",
                formatSections(plan.sectionBlueprints()))
            .replace("{resolvedResources 的格式化文本，按 forSection 分组展示}",
                formatResources(resources, plan.sectionBlueprints()))
            .replace("{personalization.strategy}",
                plan.personalization() != null ? plan.personalization().strategy() : "direct")
            .replace("{personalization.depth}",
                plan.personalization() != null ? plan.personalization().depth() : "moderate")
            .replace("{personalization.adaptations.analogyDirection}",
                getAdaptationField(plan.personalization(), "analogyDirection"))
            .replace("{personalization.adaptations.emphasizePrerequisites}",
                getAdaptationField(plan.personalization(), "emphasizePrerequisites"))
            .replace("{personalization.adaptations.buildOnRecentLearning}",
                getAdaptationField(plan.personalization(), "buildOnRecentLearning"))
            .replace("{qualitySpec.keyClaims}", formatList(plan.qualitySpec() != null ?
                plan.qualitySpec().keyClaims() : List.of()))
            .replace("{qualitySpec.forbiddenPhrases}", formatList(plan.qualitySpec() != null ?
                plan.qualitySpec().forbiddenPhrases() : List.of()))
            .replace("{qualitySpec.requireSourceCitation}", String.valueOf(
                plan.qualitySpec() != null && plan.qualitySpec().requireSourceCitation()))
            .replace("{profile.knowledgeBaseSummary}",
                Objects.toString(profile != null ? profile.getOrDefault("knowledgeBaseSummary", "") : "", ""))
            .replace("{profile.weakPoints}", formatList(profile != null ?
                profile.get("weakPoints") : null))
            .replace("{profile.learningPace}",
                Objects.toString(profile != null ? profile.getOrDefault("learningPace", "moderate") : "moderate"))
            .replace("{question}", question);
    }

    public String buildRegeneratePrompt(String originalAnswer, String sectionTitle,
                                         String action, String instruction,
                                         SectionBlueprint sectionBlueprint,
                                         List<RetrievedChunk> sectionResources) {
        String base = promptLoader.get("tutoring/regenerate-section");

        String actionDesc = switch (action) {
            case "simplify" -> "请用更通俗的语言重写此 section。减少术语，多用类比。保持核心信息不丢失。";
            case "switch_angle" -> "请换一种教学策略重新解释同一概念。如果原来用的是类比，现在尝试用图解或直接推导。";
            case "followup" -> "学生针对此 section 追问：" + (instruction != null ? instruction : "");
            case "more_examples" -> "请为此 section 追加一个额外的具体示例。";
            default -> action;
        };

        return base
            .replace("{original_full_answer}", originalAnswer)
            .replace("{sectionTitle}", sectionTitle)
            .replace("{action 描述}", actionDesc)
            .replace("{instruction}", instruction != null ? instruction : "")
            .replace("{current_section_content}", "")
            .replace("{sectionBlueprint}", formatSectionBlueprint(sectionBlueprint))
            .replace("{section_resolved_resources}", formatChunks(sectionResources));
    }

    private String formatSections(List<SectionBlueprint> sections) {
        if (sections == null || sections.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        for (var section : sections) {
            sb.append("---\n");
            sb.append("Section ID: ").append(section.id()).append("\n");
            sb.append("标题: ").append(section.title()).append("\n");
            sb.append("用途: ").append(section.purpose()).append("\n");
            sb.append("默认展开: ").append(section.expandDefault()).append("\n");
            if (section.expectedDiagram() != null) {
                sb.append("需要图解: ").append(section.expectedDiagram().id())
                    .append(" (").append(section.expectedDiagram().type()).append(")\n");
            }
            sb.append("生成提示: ").append(section.generationHint()).append("\n");
        }
        return sb.toString();
    }

    private String formatResources(ResolvedResources resources, List<SectionBlueprint> sections) {
        if (resources == null || resources.isEmpty()) return "无可用资源，请基于模型自有知识生成";
        StringBuilder sb = new StringBuilder();
        if (sections != null) {
            for (var section : sections) {
                if (section.resourceRefs() != null && !section.resourceRefs().isEmpty()) {
                    sb.append("## ").append(section.title()).append(" 的资源\n");
                    for (String refId : section.resourceRefs()) {
                        List<RetrievedChunk> chunks = resources.getForRequirement(refId);
                        if (!chunks.isEmpty()) {
                            sb.append(formatChunks(chunks));
                        } else {
                            sb.append("（资源 ").append(refId).append(" 未能获取）\n");
                        }
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    private String formatChunks(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var chunk : chunks) {
            sb.append("- [ref:").append(chunk.chunkId()).append("] ")
                .append(chunk.content()).append("\n");
            sb.append("  来源: ").append(chunk.sourceTitle())
                .append(" / ").append(chunk.sourceSection()).append("\n");
        }
        return sb.toString();
    }

    private String formatSectionBlueprint(SectionBlueprint section) {
        if (section == null) return "";
        return "ID: " + section.id() + "\n标题: " + section.title() +
            "\n用途: " + section.purpose() + "\n生成提示: " + section.generationHint();
    }

    @SuppressWarnings("unchecked")
    private String getAdaptationField(TeachingPersonalization p, String field) {
        if (p == null || p.adaptations() == null) return "";
        Object val = p.adaptations().get(field);
        if (val instanceof List<?> list) {
            return list.stream().map(Object::toString).collect(Collectors.joining("、"));
        }
        return Objects.toString(val, "");
    }

    @SuppressWarnings("unchecked")
    private String formatList(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream().map(Object::toString).collect(Collectors.joining("、"));
        }
        return Objects.toString(obj, "");
    }

    // ===== Guided Mode Prompt Methods =====

    /**
     * 调用点②：引导讲解 + 提问生成的系统提示词。
     */
    public String buildGuidedGuidancePrompt(GuidedStep step, ResolvedResources resources,
                                             Map<String, Object> profile, String question,
                                             GuidedDialogueHistory history,
                                             int stepIndex, int totalSteps) {
        String base = promptLoader.get("tutoring/guided-generator-system");

        String originalQuestion = history != null ? history.originalQuestion() : question;
        String previousTurns = formatPreviousTurns(history);
        String resourcesText = formatStepResources(resources, step.resourceRefs());

        return base
            .replace("{originalQuestion}", originalQuestion)
            .replace("{stage}", Objects.toString(step.stage(), ""))
            .replace("{title}", Objects.toString(step.title(), ""))
            .replace("{guidanceHint}", Objects.toString(step.guidanceHint(), ""))
            .replace("{stepIndex}", String.valueOf(stepIndex))
            .replace("{totalSteps}", String.valueOf(totalSteps))
            .replace("{resources}", resourcesText)
            .replace("{profile.knowledgeBaseSummary}",
                Objects.toString(profile != null ? profile.getOrDefault("knowledgeBaseSummary", "") : "", ""))
            .replace("{profile.cognitiveStyle}",
                Objects.toString(profile != null ? profile.getOrDefault("cognitiveStyle", "textual") : "textual"))
            .replace("{profile.weakPoints}", formatList(profile != null ? profile.get("weakPoints") : null))
            .replace("{profile.learningPace}",
                Objects.toString(profile != null ? profile.getOrDefault("learningPace", "moderate") : "moderate"))
            .replace("{previousTurns}", previousTurns);
    }

    /**
     * 调用点④：过渡反馈的系统提示词（答对后推进下一步前）。
     */
    public String buildGuidedFeedbackPrompt(GuidedStep step, String studentAnswer,
                                             String evaluation, GuidedStep nextStep,
                                             GuidedDialogueHistory history) {
        String originalQuestion = history != null ? history.originalQuestion() : "";
        String previousTurnsSummary = formatPreviousTurns(history);

        return """
            你是一位引导教师。学生刚完成了一个步骤，你要生成一段简短的过渡反馈（50-100字），然后自然引出下一步。

            ## 原始问题
            %s

            ## 当前步骤
            - 阶段：%s: %s
            - 学生回答：%s
            - 评估结果：%s

            ## 下一步
            - 下一阶段：%s: %s

            ## 之前步骤摘要
            %s

            ## 要求
            1. 先肯定学生在当前步骤的表现（1-2句）
            2. 自然过渡到下一步要思考的方向（1-2句）
            3. 不要给出下一步的答案或具体内容
            4. 语气鼓励、简洁
            """.formatted(
                originalQuestion,
                step.stage(), step.title(),
                studentAnswer,
                evaluation,
                nextStep != null ? nextStep.stage() : "",
                nextStep != null ? nextStep.title() : "",
                previousTurnsSummary
            );
    }

    /**
     * 调用点⑤：揭示答案解释的系统提示词。
     */
    public String buildGuidedRevealedPrompt(GuidedStep step, GuidedStepState state,
                                             String previousAttempts,
                                             GuidedDialogueHistory history) {
        String originalQuestion = history != null ? history.originalQuestion() : "";
        String previousTurnsSummary = formatPreviousTurns(history);

        return """
            你是一位引导教师。学生在当前步骤多次尝试后选择查看答案，你需要给出答案并解释为什么。

            ## 原始问题
            %s

            ## 当前步骤
            - 阶段：%s: %s
            - 提出的问题：%s
            - 预期答案要点：%s
            - 学生之前的尝试：%s

            ## 之前步骤摘要
            %s

            ## 要求
            1. 给出本步骤的正确答案
            2. 解释为什么这个答案是正确的
            3. 结合学生之前的错误尝试，指出他们可能在哪里卡住了
            4. 鼓励学生：卡住是正常的，理解答案后继续
            5. 不要给出后续步骤的答案
            """.formatted(
                originalQuestion,
                step.stage(), step.title(),
                state != null ? state.question() : "",
                step.expectedAnswer(),
                previousAttempts,
                previousTurnsSummary
            );
    }

    /**
     * 调用点⑥：总结回顾的系统提示词。
     */
    public String buildGuidedSummaryPrompt(ExecutionPlan plan, String question,
                                            GuidedDialogueHistory history,
                                            Map<String, Object> profile) {
        String originalQuestion = history != null ? history.originalQuestion() : question;
        String fullStepsSummary = formatFullStepsSummary(history);

        String questionType = "";
        String keyConcepts = "";
        String realIntent = "";
        if (plan.questionAnalysis() != null) {
            questionType = plan.questionAnalysis().questionType();
            keyConcepts = formatList(plan.questionAnalysis().keyConcepts());
            realIntent = plan.questionAnalysis().realIntent();
        }

        return """
            ## 系统角色
            你是一位引导教师，现在要帮助学生回顾整个解题过程。

            ## 原始问题
            %s

            ## 问题分析
            - 题型：%s
            - 核心概念：%s
            - 学生真实意图：%s

            ## 完整解题步骤回顾
            %s

            ## 学生画像
            - 知识基础：%s
            - 薄弱点：%s

            ## 你的任务
            1. 引导学生回顾完整的解题思路链
            2. 强调每个阶段的关键思考点
            3. 指出学生在哪些步骤表现出色，哪些步骤有困难
            4. 指出可以迁移到其他题目的通用方法
            5. 鼓励学生反思自己的思考过程

            ## 约束
            - 语气鼓励、总结性
            - 篇幅 200-400 字
            """.formatted(
                originalQuestion,
                questionType,
                keyConcepts,
                realIntent,
                fullStepsSummary,
                Objects.toString(profile != null ? profile.getOrDefault("knowledgeBaseSummary", "") : "", ""),
                formatList(profile != null ? profile.get("weakPoints") : null)
            );
    }

    /** 格式化之前步骤的交互历史（详细版，用于 streamGuidance） */
    private String formatPreviousTurns(GuidedDialogueHistory history) {
        if (history == null || history.turns() == null || history.turns().isEmpty()) {
            return "无（这是第一步）";
        }
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        for (var turn : history.turns()) {
            idx++;
            sb.append("### 步骤").append(idx).append("：").append(turn.title()).append("\n");
            sb.append("- AI引导：").append(truncate(turn.guidanceContent(), 100)).append("\n");
            sb.append("- AI提问：").append(turn.question()).append("\n");
            sb.append("- 学生回答：").append(turn.studentAnswer()).append("\n");
            sb.append("- AI反馈：").append(truncate(turn.feedback(), 100)).append("\n\n");
        }
        return sb.toString();
    }

    /** 格式化完整步骤摘要（用于总结） */
    private String formatFullStepsSummary(GuidedDialogueHistory history) {
        if (history == null || history.turns() == null || history.turns().isEmpty()) {
            return "无步骤记录";
        }
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        for (var turn : history.turns()) {
            idx++;
            sb.append("### 步骤").append(idx).append("：").append(turn.title())
              .append("（").append(turn.stage()).append("）\n");
            sb.append("- AI提问：").append(turn.question()).append("\n");
            sb.append("- 学生回答：").append(turn.studentAnswer()).append("\n");
            sb.append("- 评估结果：").append(turn.evaluation())
              .append("（尝试").append(turn.attempts()).append("次）\n\n");
        }
        return sb.toString();
    }

    /** 按步骤的 resourceRefs 过滤并格式化资源 */
    private String formatStepResources(ResolvedResources resources, String resourceRefs) {
        if (resources == null || resources.isEmpty() || resourceRefs == null || resourceRefs.isBlank()) {
            return "无可用资源";
        }
        StringBuilder sb = new StringBuilder();
        for (String refId : resourceRefs.split(",")) {
            refId = refId.trim();
            List<RetrievedChunk> chunks = resources.getForRequirement(refId);
            if (!chunks.isEmpty()) {
                for (var chunk : chunks) {
                    sb.append("- [ref:").append(chunk.chunkId()).append("] ")
                        .append(chunk.content()).append("\n");
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : "无可用资源";
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
