package com.learnthink.core.agent.impl;

import com.learnthink.core.agent.runtime.AgentContext;
import com.learnthink.core.agent.runtime.AgentResult;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import com.learnthink.core.config.PromptLoader;
import com.learnthink.core.domain.entity.BookInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class CurriculumPlanner {

    private static final Logger log = LoggerFactory.getLogger(CurriculumPlanner.class);
    private final ChatClient chatClient;
    private final PromptLoader promptLoader;
    private final BookInfoTool bookInfoTool;
    private final ObjectMapper mapper = new ObjectMapper();

    public CurriculumPlanner(@Qualifier("reasoningChatClientBuilder") ChatClient.Builder chatClientBuilder,
                             PromptLoader promptLoader,
                             BookInfoTool bookInfoTool) {
        this.chatClient = chatClientBuilder.build();
        this.promptLoader = promptLoader;
        this.bookInfoTool = bookInfoTool;
    }

    /**
     * 执行课程资源规划
     * <p>根据用户画像、证据来源和资源类型列表，
     * 调用 LLM 生成结构化的资源计划（含子主题分解和条目分配）。</p>
     *
     * @param profile       用户画像摘要
     * @param mergedSources 合并后的证据来源列表
     * @param topic         规划主题
     * @param resourceTypes 需要生成的资源类型列表
     * @param feedback      重新规划时的前次计划反馈，首次尝试时为 null
     * @param ctx           Agent 执行上下文
     * @return 资源计划结果
     */
    public AgentResult<ResourceGenerationState.ResourcePlan> plan(
        ResourceGenerationState.ProfileSummary profile,
        List<ResourceGenerationState.SourceItem> mergedSources,
        String topic,
        List<String> resourceTypes,
        String feedback,
        AgentContext ctx) {
        return plan(profile, mergedSources, topic, resourceTypes, feedback, ctx, null);
    }

    /**
     * 使用聊天规划器的可选用户意图约束进行计划
     * {@code generationMeta} 包含 estimatedCount、difficulty、specialRequirements 等
     */
    public AgentResult<ResourceGenerationState.ResourcePlan> plan(
        ResourceGenerationState.ProfileSummary profile,
        List<ResourceGenerationState.SourceItem> mergedSources,
        String topic,
        List<String> resourceTypes,
        String feedback,
        AgentContext ctx,
        Map<String, Object> generationMeta) {

        log.info("=== CurriculumPlanner START === topic={}, types={}, feedback={}",
                topic, resourceTypes, feedback != null ? "with feedback" : "initial");
        Instant start = Instant.now();
        String contextInfo = feedback != null
            ? "REPLANNING with feedback: " + feedback
            : "Initial planning";

        String systemPrompt = promptLoader.get("agent/planner");
        ctx.observation().onPrompt("CurriculumPlanner", systemPrompt,
            Map.of("topic", topic, "types", resourceTypes, "replanning", feedback != null));

        try {
            String sourcesSummary = buildSourcesSummary(mergedSources);
            log.info("Sources summary: {}", sourcesSummary.substring(0, Math.min(100, sourcesSummary.length())));
            String profileSummaryText = buildProfileSummaryText(profile);
            log.info("Profile summary prepared ({} chars)", profileSummaryText.length());
            String courseToc = buildCourseToc(ctx.courseId());

            String constraintsText = buildConstraintsText(generationMeta);

            String prompt = String.format("""
                {profile_summary}: %s
                {merged_sources_summary}: %s
                {course_toc}: %s
                {user_constraints}: %s
                {topic}: %s
                {resource_types}: %s
                {plan_feedback}: %s
                Context: %s
                """,
                profileSummaryText, sourcesSummary, courseToc, constraintsText, topic,
                String.join(", ", resourceTypes),
                feedback != null ? feedback : "N/A (initial plan)",
                contextInfo);

            log.info("Calling LLM for planning");
            String response = chatClient.prompt()
                .messages(new SystemMessage(systemPrompt), new UserMessage(prompt))
                .call()
                .content();

            long elapsed = java.time.Duration.between(start, Instant.now()).toMillis();
            log.info("[AI-RESPONSE][CurriculumPlanner] plan ({}ms) length={} chars\n{}",
                elapsed,
                response != null ? response.length() : 0,
                response != null ? response.substring(0, Math.min(2000, response.length())) : "null");
            ctx.observation().onResponse("CurriculumPlanner", response, elapsed,
                AgentResult.TokenUsage.ZERO);

            var plan = parsePlan(response);
            log.info("Plan parsed successfully. SubTopics: {}, Items: {}, Outline sections: {}",
                    plan.subTopics().size(), plan.items().size(), countOutlineSections(plan.topicOutline()));
            ctx.observation().onDecision("CurriculumPlanner", "plan_created",
                plan.subTopics().size() + " sub-topics, " + plan.items().size() + " items");

            log.info("CurriculumPlanner completed successfully");
            return AgentResult.of(plan, AgentResult.TokenUsage.ZERO, elapsed,
                Map.of("agent", "CurriculumPlanner", "itemCount", plan.items().size(),
                       "replan", feedback != null));

        } catch (Exception e) {
            log.error("CurriculumPlanner failed: {}", e.getMessage(), e);
            ctx.observation().onError("CurriculumPlanner", e);
            return AgentResult.error("Plan generation failed: " + e.getMessage());
        }
    }

    /**
     * 从聊天规划器的 generationMeta 构建约束文本
     * <p>解决了信息断层问题：当用户说"生成一个关于 X 的视频"时，
     * 规划器对用户意图的理解（estimatedCount=1）能够真正传达到 CurriculumPlanner。</p>
     */
    private String buildConstraintsText(Map<String, Object> generationMeta) {
        if (generationMeta == null || generationMeta.isEmpty()) {
            return "N/A (no user-specified constraints — plan freely)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("The USER explicitly requested these resources. You MUST respect these constraints:\n");
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) generationMeta.get("items");
        if (items != null && !items.isEmpty()) {
            sb.append("- Requested resource items (exact count: ").append(items.size()).append("):\n");
            for (var item : items) {
                sb.append("  * ").append(item.get("type"))
                  .append(" — ").append(item.get("focus")).append("\n");
            }
            sb.append("  Generate EXACTLY one resource per item above. Do NOT add or remove items.\n");
        }
        if (generationMeta.get("difficulty") != null) {
            String rawDifficulty = generationMeta.get("difficulty").toString();
            sb.append("- Difficulty: ").append(mapDifficultyToBloom(rawDifficulty)).append("\n");
        }
        if (generationMeta.get("goalSummary") != null) {
            sb.append("- User's goal: ").append(generationMeta.get("goalSummary")).append("\n");
        }
        if (generationMeta.get("specialRequirements") != null) {
            sb.append("- Special requirements: ").append(generationMeta.get("specialRequirements")).append("\n");
        }
        sb.append("\nIMPORTANT: Generate exactly the number and types of resources listed above. ");
        sb.append("Match the scope to what the user actually asked for.");
        return sb.toString();
    }

    private String buildProfileSummaryText(ResourceGenerationState.ProfileSummary profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("\nStudent Profile:");
        sb.append("\n- Weak areas: ").append(profile.weakTop());
        sb.append("\n- Learning style: ").append(profile.style());
        sb.append("\n- Time per day: ").append(profile.minutesPerDay()).append(" min");
        sb.append("\n- Goal: ").append(profile.goal());
        sb.append("\n- Dimensions covered: ").append(profile.dimensionCount()).append("/7");

        if (profile.currentChapter() != null && !profile.currentChapter().isBlank()) {
            sb.append("\n- Current chapter: ").append(profile.currentChapter());
        }

        // v4.0：按范围分组的知识点锚点
        List<ResourceGenerationState.KpAnchor> anchors = profile.kpAnchors();
        if (anchors != null && !anchors.isEmpty()) {
            var coreGaps = anchors.stream()
                .filter(a -> "core_curriculum".equals(a.scope()) && "weak".equals(a.relationType()))
                .toList();
            var prereqGaps = anchors.stream()
                .filter(a -> "prerequisite".equals(a.scope()) && "weak".equals(a.relationType()))
                .toList();
            var coreInterests = anchors.stream()
                .filter(a -> "interest".equals(a.relationType())
                    && !"extracurricular".equals(a.scope()))
                .toList();
            var extraInterests = anchors.stream()
                .filter(a -> "extracurricular".equals(a.scope()))
                .toList();

            if (!coreGaps.isEmpty()) {
                sb.append("\n- Core curriculum gaps (HIGHEST priority):");
                for (var a : coreGaps) {
                    sb.append("\n  . ").append(a.kpName());
                    if (a.chapterTitle() != null) sb.append(" [").append(a.chapterTitle()).append("]");
                    sb.append(" (confidence: ").append(String.format("%.0f%%", a.confidence() * 100)).append(")");
                }
            }
            if (!prereqGaps.isEmpty()) {
                sb.append("\n- Prerequisite gaps (address only if needed for current chapter):");
                for (var a : prereqGaps) {
                    sb.append("\n  . ").append(a.kpName());
                    if (a.chapterTitle() != null) sb.append(" [").append(a.chapterTitle()).append("]");
                }
            }
            if (!coreInterests.isEmpty()) {
                sb.append("\n- In-curriculum interests (low priority):");
                for (var a : coreInterests) {
                    sb.append("\n  . ").append(a.kpName());
                    sb.append(" [").append(a.scope()).append("]");
                }
            }
            if (!extraInterests.isEmpty()) {
                sb.append("\n- Extracurricular interests (reading only, do NOT make core resources):");
                for (var a : extraInterests) {
                    sb.append("\n  . ").append(a.kpName());
                }
            }
        }
        return sb.toString();
    }

    /**
     * 从 BookInfo.toc 提取课程教材的完整章节目录，供规划器了解教材全局结构。
     * 解决 RAG 检索不确定导致规划器遗漏章节的问题。
     */
    private String buildCourseToc(String courseId) {
        try {
            BookInfo bookInfo = bookInfoTool.resolveBookInfo(courseId);
            if (bookInfo == null || bookInfo.getToc() == null) return "暂无教材目录";
            List<Map<String, Object>> tocList = mapper.readValue(bookInfo.getToc(),
                new TypeReference<List<Map<String, Object>>>() {});
            if (tocList == null || tocList.isEmpty()) return "暂无教材目录";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tocList.size(); i++) {
                Map<String, Object> node = tocList.get(i);
                String title = (String) node.getOrDefault("title", "");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
                sb.append("\n  Ch").append(i + 1).append(" ").append(title);
                if (children != null) {
                    for (int j = 0; j < children.size() && j < 8; j++) {
                        String secTitle = (String) children.get(j).getOrDefault("title", "");
                        if (!secTitle.isEmpty()) sb.append("\n    ").append(secTitle);
                    }
                    if (children.size() > 8) sb.append("\n    ... (共").append(children.size()).append("节)");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("构建课程目录失败: {}", e.getMessage());
            return "暂无教材目录";
        }
    }

    private String buildSourcesSummary(List<ResourceGenerationState.SourceItem> sources) {
        if (sources == null || sources.isEmpty()) return "No sources available";
        return sources.stream()
            .limit(10)
            .map(s -> {
                String book = s.bookTitle() != null && !s.bookTitle().isBlank() ? "《" + s.bookTitle() + "》" : "";
                String chapter = s.chapterTitle() != null && !s.chapterTitle().isBlank() ? s.chapterTitle() : "";
                String bookType = s.bookType() != null && !s.bookType().isBlank() ? "[" + s.bookType() + "]" : "";
                String ref = bookType + book + chapter;
                return (ref.isBlank() ? s.docId() : ref) + ": " + s.locator();
            })
            .collect(Collectors.joining("; "));
    }

    private ResourceGenerationState.ResourcePlan parsePlan(String json) {
        try {
            var node = mapper.readTree(json);

            // 解析子主题（新格式）；缺失时回退到单主题包装
            List<ResourceGenerationState.SubTopic> subTopics;
            if (node.has("subTopics") && node.get("subTopics").isArray()) {
                subTopics = mapper.convertValue(node.get("subTopics"),
                    mapper.getTypeFactory().constructCollectionType(List.class,
                        ResourceGenerationState.SubTopic.class));
            } else {
                subTopics = List.of(new ResourceGenerationState.SubTopic(
                    0, node.get("topicOutline").asText("Overview"),
                    "Full topic (legacy format — no sub-topic decomposition)",
                    List.of(), 60, "medium"));
            }

            // 解析条目及其 subTopicIndex（缺失时默认为 0）
            List<ResourceGenerationState.ResourcePlanItem> items =
                mapper.convertValue(node.get("items"),
                    mapper.getTypeFactory().constructCollectionType(List.class,
                        ResourceGenerationState.ResourcePlanItem.class));
            // 标准化类型名称：LLM 可能输出 "document" 而非 "doc"
            items = items.stream()
                .map(item -> {
                    ResourceGenerationState.ResourcePlanItem normalized =
                        "document".equals(item.type())
                        ? new ResourceGenerationState.ResourcePlanItem(
                            "doc", item.title(), item.difficulty(), item.estimatedMinutes(),
                            item.format(), item.keyPoints(), item.personalizationNote(),
                            item.subTopicIndex(), item.activityId())
                        : item;
                    return normalized;
                })
                .toList();

            return new ResourceGenerationState.ResourcePlan(
                node.get("topicOutline").asText(),
                subTopics,
                items,
                mapper.convertValue(node.get("pushReason"), List.class),
                mapper.convertValue(node.get("queries"), List.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse plan JSON: " + e.getMessage(), e);
        }
    }

    private int countOutlineSections(String outline) {
        return (int) outline.lines().filter(l -> l.startsWith("##")).count();
    }

    /**
     * 将聊天规划器的难度标签（beginner/intermediate/advanced）
     * 映射到资源规划器提示词使用的 Bloom 分类法 1-5 级别
     * <p>聊天规划器使用自然语言标签；资源规划器期望 1-5 整数。
     * 此方法生成显式映射，避免 LLM 自行猜测。</p>
     */
    private String mapDifficultyToBloom(String difficulty) {
        if (difficulty == null) return "3 (default medium)";
        return switch (difficulty.toLowerCase().trim()) {
            case "beginner" -> "1-2 (基础难度：侧重识记和理解，使用简单术语，多举例)";
            case "intermediate", "medium" -> "3 (中等难度：侧重应用和分析，平衡理论与实践)";
            case "advanced" -> "4-5 (高难度：侧重评价和创造，引入挑战性问题与开放设计)";
            default -> difficulty + " — 请映射到 1-5 Bloom 层级 (1=识记, 2=理解, 3=应用, 4=分析, 5=评价/创造)";
        };
    }
}
