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
}
