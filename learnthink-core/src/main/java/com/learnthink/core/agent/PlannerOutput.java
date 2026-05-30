package com.learnthink.core.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 规划层的标准化输出
 * <p>用强类型替换了临时性的 [CONTROL] 文本块解析。
 * 聊天规划边界处所有 LLM 生成的 JSON 都解析为此记录，
 * 消除了手工处理 {@code Map<String,Object>} 及随之而来的
 * {@code @SuppressWarnings("unchecked")}。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlannerOutput(
    Intent intent,
    ResourceRequirements resourceRequirements,
    PlanGenerationRequirements planGenerationRequirements,
    Clarification clarification,
    ReplyPlan replyPlan
) {
    // ---- 子类型 ----

    /** 用户意图：resource_generation / learning_plan / chat */
    public record Intent(
        /** 意图类型 */
        @JsonProperty(required = true) String type,
        /** 置信度 0.0~1.0 */
        double confidence,
        /** 是否需要追问澄清 */
        @JsonProperty(required = true) boolean needsClarification
    ) {}

    /** 资源生成需求 */
    public record ResourceRequirements(
        /** 生成主题 */
        String topic,
        /** 目标摘要 */
        String goalSummary,
        /** 资源项列表，每项对应一个待生成资源 */
        List<ResourceItem> items,
        /** 难度级别 */
        String difficulty,
        /** 重点领域 */
        List<String> focusAreas,
        /** 特殊要求 */
        String specialRequirements
    ) {}

    /** 单个资源项：类型 + 侧重点 */
    public record ResourceItem(
        /** 资源类型：doc | quiz | mindmap | video | code | reading */
        String type,
        /** 该资源的侧重点/子主题 */
        String focus
    ) {}

    /** 学习计划生成需求 — AI 分析总结的用户需求自然语言文本 */
    public record PlanGenerationRequirements(
        /** 精准概括用户的学习计划需求，自然语言文本，将原样喂给大计划生成器 */
        String requirementText
    ) {}

    /** 追问澄清信息 */
    public record Clarification(
        /** 追问问题列表 */
        List<ClarificationQuestion> questions,
        /** 缺失的信息项 */
        List<String> missingInfo
    ) {}

    /** 追问问题 */
    public record ClarificationQuestion(
        /** 问题内容 */
        String question,
        /** 提问原因 */
        String reason,
        /** 可选项 */
        List<String> options
    ) {}

    /** 回复计划 */
    public record ReplyPlan(
        /** 回复策略：direct_answer / offer_generation / ask_clarification */
        @JsonProperty(required = true) String strategy,
        /** 回复要点列表 */
        List<String> keyPoints,
        /** 语气风格 */
        String tone,
        /** 是否展示生成确认按钮 */
        boolean shouldShowOffer
    ) {}

    // ---- 便捷判定方法 ----

    /** 前端是否应展示生成确认按钮 */
    public boolean shouldShowGenerationButtons() {
        return replyPlan != null && replyPlan.shouldShowOffer()
            && intent != null && !intent.needsClarification();
    }

    /** 是否为追问/跟进（按钮被抑制） */
    public boolean isClarification() {
        return intent != null && intent.needsClarification();
    }

    /** 是否意图生成资源且信息充足 */
    public boolean isGenerationReady() {
        return intent != null
            && "resource_generation".equals(intent.type())
            && !intent.needsClarification()
            && resourceRequirements != null;
    }

    /** 是否意图生成学习计划且信息充足 */
    public boolean isPlanGenerationReady() {
        return intent != null
            && "learning_plan".equals(intent.type())
            && !intent.needsClarification()
            && planGenerationRequirements != null;
    }

    // ---- Serialization ----

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static PlannerOutput fromJson(String json) {
        try {
            return MAPPER.readValue(json, PlannerOutput.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse PlannerOutput JSON: " + e.getMessage(), e);
        }
    }

    /**
     * 从可能包含 [CONTROL] 块的原始规划器文本中解析
     * <p>提取 [CONTROL] 与 ---CONTROL_END--- 标记之间的 JSON，
     * 如有 markdown 代码围栏则去除。</p>
     *
     * @param plannerText 原始规划器文本
     * @return 解析结果，无有效 [CONTROL] 时返回 null
     */
    public static PlannerOutput fromPlannerText(String plannerText) {
        int controlStart = plannerText.indexOf("[CONTROL]");
        int controlEnd = plannerText.indexOf("---CONTROL_END---");

        if (controlStart < 0 || controlEnd < 0) {
            return null;
        }

        String controlBlock = plannerText.substring(controlStart + 9, controlEnd).trim();
        String json = controlBlock;
        int jsonStart = controlBlock.indexOf("{");
        int jsonEnd = controlBlock.lastIndexOf("}");
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            json = controlBlock.substring(jsonStart, jsonEnd + 1);
        }

        return fromJson(json);
    }

    /**
     * 兜底：当未找到有效 [CONTROL] 时创建纯聊天的规划器输出
     * @return 纯聊天模式的 PlannerOutput 实例
     */
    public static PlannerOutput fallbackChatOnly() {
        return new PlannerOutput(
            new Intent("chat", 1.0, false),
            null,
            null,
            null,
            new ReplyPlan("direct_answer", List.of(), "neutral", false)
        );
    }
}
