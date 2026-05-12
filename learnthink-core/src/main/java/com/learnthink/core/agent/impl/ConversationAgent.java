package com.learnthink.core.agent.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.framework.*;
import com.learnthink.core.config.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * Conversation agent for profile-building dialogue.
 *
 * <h3>Design (from 05-画像对话 v3.0):</h3>
 * <ul>
 *   <li>L3 Judgment-level autonomy: decides when enough information has been collected</li>
 *   <li>Intelligent sufficiency evaluation via structured confidence scoring (NOT string matching)</li>
 *   <li>Returns chat reply first (non-blocking), then async triggers ProfileAgent</li>
 *   <li>Plan-Act-Observe-Reflect loop per conversation turn</li>
 * </ul>
 *
 * <h3>Key difference from ChatServiceImpl:</h3>
 * <p>ChatServiceImpl was a standalone service calling ChatClient directly.
 * ConversationAgent is an Agent running on the StateGraph engine, sharing AgentContext
 * with other agents, and pushing thinking chain events via Observation.</p>
 */
@Component
public class ConversationAgent implements Agent<ConversationAgent.ConversationInput, ConversationAgent.ConversationOutput> {

    private static final Logger log = LoggerFactory.getLogger(ConversationAgent.class);
    private final ChatClient chatClient;
    private final PromptLoader promptLoader;
    private final ObjectMapper mapper = new ObjectMapper();

    public ConversationAgent(@Qualifier("chatChatClientBuilder") ChatClient.Builder chatClientBuilder,
                             PromptLoader promptLoader) {
        this.chatClient = chatClientBuilder.build();
        this.promptLoader = promptLoader;
    }

    @Override
    public String name() { return "ConversationAgent"; }

    @Override
    public boolean isRetryable() { return false; }

    // ================================================================
    // Plan-Act-Observe-Reflect loop (per conversation turn)
    // ================================================================

    @Override
    public AgentResult<ConversationOutput> execute(ConversationInput input, AgentContext ctx) {
        Instant start = Instant.now();

        // === PLAN ===
        ctx.observation().onPrompt(name(),
            "Evaluating conversation state (round " + input.roundNumber() + ")",
            Map.of("courseId", input.courseId(), "historySize", input.conversationHistory().size()));

        // Step 1: Generate reply
        String systemPrompt = input.systemPromptOverride() != null && !input.systemPromptOverride().isBlank()
            ? input.systemPromptOverride()
            : promptLoader.get("agent/conversation");
        List<Message> messages = buildMessages(systemPrompt, input.conversationHistory());
        String reply = chatClient.prompt().messages(messages).call().content();

        // === ACT ===
        ctx.observation().onResponse(name(), "Reply generated (" + reply.length() + " chars)",
            java.time.Duration.between(start, Instant.now()).toMillis(), AgentResult.TokenUsage.ZERO);

        // Step 2: Evaluate sufficiency (structured confidence scoring)
        SufficiencyResult sufficiency = evaluateSufficiency(input.conversationHistory());

        // === OBSERVE ===
        ctx.observation().onDecision(name(),
            sufficiency.sufficient() ? "SUFFICIENT" : "CONTINUE",
            "Covered " + sufficiency.coveredCount() + "/7 dimensions, confidence=" + sufficiency.overallConfidence());

        // Store in context for OrchestratorAgent
        ctx.put("profile.sufficient", sufficiency.sufficient());
        ctx.put("profile.covered_count", sufficiency.coveredCount());
        ctx.put("profile.confidence", sufficiency.overallConfidence());
        ctx.put("profile.missing_dimensions", sufficiency.missingDimensions());

        // === REFLECT ===
        // (L3 judgment: the agent itself decides whether to end the conversation)
        // Reflection is captured in the observation above

        long elapsed = java.time.Duration.between(start, Instant.now()).toMillis();
        ConversationOutput output = new ConversationOutput(reply, sufficiency);
        return AgentResult.of(output, AgentResult.TokenUsage.ZERO, elapsed,
            Map.of("agent", name(), "sufficient", sufficiency.sufficient(),
                   "coveredCount", sufficiency.coveredCount()));
    }

    // ================================================================
    // Streaming reply (true streaming, returns Flux<String>)
    // ================================================================

    /**
     * Stream the LLM reply token-by-token. Does NOT evaluate sufficiency —
     * that happens post-stream in ChatServiceImpl.
     */
    public Flux<String> streamReply(ConversationInput input) {
        String systemPrompt = input.systemPromptOverride() != null && !input.systemPromptOverride().isBlank()
            ? input.systemPromptOverride()
            : promptLoader.get("agent/conversation");
        List<Message> messages = buildMessages(systemPrompt, input.conversationHistory());
        return chatClient.prompt().messages(messages).stream().content();
    }

    // ================================================================
    // Sufficiency evaluation (structured confidence scoring)
    // Replaces the fragile [PROFILE_READY] string-marker approach
    // ================================================================

    public SufficiencyResult evaluateSufficiency(List<Map<String, String>> conversationHistory) {
        String evalPrompt = """
            Evaluate the conversation for student profile coverage.
            Score each dimension 0-1:
              - 0: completely unmentioned
              - 0.3-0.5: indirectly inferred
              - 0.6-0.8: explicitly mentioned but vague
              - 0.9-1.0: explicitly and clearly stated

            Dimensions:
            1. major_context — major, course, current chapter
            2. knowledge_basis — strengths and weaknesses
            3. learning_goal — target, deadline, sub-goals
            4. cognitive_style — preferred learning methods, things to avoid
            5. learning_pace — minutes per day, days per week, urgency
            6. interest_direction — topics of interest, applications
            7. error_pattern — common mistake types

            Output strict JSON:
            {
              "dimensions": {
                "major_context": {"score": 0.9, "evidence": "brief quote"},
                ...
              },
              "covered_count": 4,
              "overall_sufficient": false,
              "missing_dimensions": ["learning_pace", "interest_direction"],
              "suggested_question": "..."
            }

            Rule: sufficient = covered_count >= 4 AND all covered dimensions score >= 0.7
            """;

        try {
            StringBuilder transcript = new StringBuilder();
            for (var msg : conversationHistory) {
                String role = msg.getOrDefault("role", "user");
                String content = msg.getOrDefault("content", "");
                if ("user".equals(role) || "assistant".equals(role)) {
                    transcript.append(role).append(": ").append(content).append("\n");
                }
            }

            String response = chatClient.prompt()
                .messages(new SystemMessage(evalPrompt), new UserMessage(transcript.toString()))
                .call().content();

            // Parse structured JSON
            String json = response;
            if (json.contains("```json")) {
                json = json.substring(json.indexOf("```json") + 7, json.lastIndexOf("```"));
            } else if (json.contains("```")) {
                json = json.substring(json.indexOf("```") + 3, json.lastIndexOf("```"));
            }
            json = json.trim();

            Map<String, Object> result = mapper.readValue(json, new TypeReference<>() {});

            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> dims =
                (Map<String, Map<String, Object>>) result.get("dimensions");

            int coveredCount = ((Number) result.get("covered_count")).intValue();
            boolean sufficient = Boolean.TRUE.equals(result.get("overall_sufficient"));
            double overallConfidence = dims.values().stream()
                .filter(d -> d.containsKey("score"))
                .mapToDouble(d -> ((Number) d.get("score")).doubleValue())
                .average().orElse(0);

            @SuppressWarnings("unchecked")
            List<String> missing = (List<String>) result.getOrDefault("missing_dimensions", List.of());
            String suggested = (String) result.getOrDefault("suggested_question", "");

            return new SufficiencyResult(sufficient, coveredCount, overallConfidence, missing, suggested);

        } catch (Exception e) {
            log.warn("Sufficiency evaluation failed, defaulting to CONTINUE: {}", e.getMessage());
            return new SufficiencyResult(false, 0, 0, List.of(), "");
        }
    }

    // ================================================================
    // Resource generation intent detection (v3.1)
    // ================================================================

    /**
     * Detect whether the user's latest message expresses intent to generate resources.
     * Returns null if no intent detected, otherwise returns parsed requirements.
     */
    public GenerationIntent detectGenerationIntent(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return null;

        String lower = userMessage.toLowerCase().trim();
        // Quick positive indicators
        boolean hasPositive = lower.contains("生成") || lower.contains("创建") ||
            lower.contains("做") || lower.contains("要") || lower.contains("可以") ||
            lower.contains("好") || lower.contains("行") || lower.contains("嗯") ||
            lower.contains("对") || lower.contains("是") || lower.contains("开始") ||
            lower.contains("来") || lower.contains("帮") || lower.contains("给");

        // Quick negative indicators
        boolean hasNegative = lower.contains("不") || lower.contains("不要") ||
            lower.contains("不用") || lower.contains("算了") || lower.contains("等等") ||
            lower.contains("先不") || lower.contains("暂") || lower.contains("再说");

        if (!hasPositive || hasNegative) return null;

        // Extract specific resource preferences from the message
        var preferences = extractResourcePreferences(lower);
        return new GenerationIntent(true, preferences);
    }

    /** Extract resource type and focus preferences from user message */
    private Map<String, Object> extractResourcePreferences(String message) {
        Map<String, Object> prefs = new java.util.LinkedHashMap<>();

        // Detect resource types mentioned
        java.util.List<String> requestedTypes = new java.util.ArrayList<>();
        if (message.contains("文档") || message.contains("讲解") || message.contains("讲义")) requestedTypes.add("doc");
        if (message.contains("题") || message.contains("练习") || message.contains("习题")) requestedTypes.add("quiz");
        if (message.contains("导图") || message.contains("思维导图") || message.contains("脑图")) requestedTypes.add("mindmap");
        if (message.contains("代码") || message.contains("编程") || message.contains("实操")) requestedTypes.add("code");
        if (message.contains("阅读") || message.contains("拓展") || message.contains("资料")) requestedTypes.add("reading");
        if (!requestedTypes.isEmpty()) prefs.put("requestedTypes", requestedTypes);

        // Detect focus areas
        if (message.contains("基础") || message.contains("入门")) prefs.put("focus", "foundation");
        if (message.contains("进阶") || message.contains("深入") || message.contains("高级")) prefs.put("focus", "advanced");
        if (message.contains("考试") || message.contains("复习") || message.contains("应试")) prefs.put("focus", "exam");

        // Detect quantity
        if (message.contains("全部") || message.contains("所有") || message.contains("5") || message.contains("五")) prefs.put("quantity", "all");
        else if (message.contains("先") || message.contains("一个") || message.contains("一种") || message.contains("试试")) prefs.put("quantity", "one");

        return prefs;
    }

    /**
     * Generate a natural invitation to create resources, tailored to the student's profile.
     */
    public String generateResourceOffer(SufficiencyResult sufficiency) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n---\n\n");

        int covered = sufficiency.coveredCount();
        if (covered >= 6) {
            sb.append("🎯 你的学习画像已经比较完善了（覆盖了" + covered + "个维度）！");
        } else {
            sb.append("📋 我已经了解了你的基本情况（覆盖了" + covered + "个维度）。");
        }

        sb.append("\n\n要不要我基于当前画像，为你**生成专属的学习资源包**？包括：\n");
        sb.append("- 📄 **讲解文档**：知识点系统讲解\n");
        sb.append("- 📝 **练习题**：巩固薄弱环节\n");
        sb.append("- 🧠 **思维导图**：知识结构可视化\n");
        sb.append("- 💻 **代码实操**：动手练习\n");
        sb.append("- 📚 **拓展阅读**：深入学习资料\n\n");
        sb.append("你可以直接说\"生成\"，或者告诉我你想侧重哪些方面～");

        return sb.toString();
    }

    /**
     * Generate clarifying questions when user wants generation but hasn't specified requirements.
     */
    public String generateClarifyingQuestion(GenerationIntent intent, SufficiencyResult sufficiency) {
        var prefs = intent.preferences();
        boolean hasTypes = prefs.containsKey("requestedTypes");

        if (!hasTypes) {
            return "好的！你想生成哪些类型的资源呢？\n\n"
                + "1. 📄 讲解文档 — 系统学习知识点\n"
                + "2. 📝 练习题 — 检验掌握程度\n"
                + "3. 🧠 思维导图 — 梳理知识脉络\n"
                + "4. 💻 代码实操 — 动手实践\n"
                + "5. 📚 拓展阅读 — 了解前沿进展\n\n"
                + "你可以选几项，或者说\"全部\"～";
        }

        if (!prefs.containsKey("focus")) {
            return "明白了！你希望侧重基础入门还是进阶深入？或者有考试复习的需求？";
        }

        // Requirements are clear enough — confirm and trigger
        return null; // null means "ready to trigger"
    }

    // ================================================================
    // Data types
    // ================================================================

    public record GenerationIntent(boolean wantsGeneration, Map<String, Object> preferences) {}

    // ================================================================
    // helpers
    // ================================================================

    private List<Message> buildMessages(String systemPrompt, List<Map<String, String>> history) {
        List<Message> messages = new java.util.ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        for (var msg : history) {
            String role = msg.getOrDefault("role", "user");
            String content = msg.getOrDefault("content", "");
            if ("user".equals(role)) {
                messages.add(new UserMessage(content));
            } else if ("assistant".equals(role)) {
                messages.add(new AssistantMessage(content));
            }
        }
        return messages;
    }

    // ================================================================
    // Data types
    // ================================================================

    public record ConversationInput(
        String courseId,
        List<Map<String, String>> conversationHistory,
        int roundNumber,
        String systemPromptOverride
    ) {}

    public record ConversationOutput(
        String reply,
        SufficiencyResult sufficiency
    ) {}

    public record SufficiencyResult(
        boolean sufficient,
        int coveredCount,
        double overallConfidence,
        List<String> missingDimensions,
        String suggestedQuestion
    ) {}
}
