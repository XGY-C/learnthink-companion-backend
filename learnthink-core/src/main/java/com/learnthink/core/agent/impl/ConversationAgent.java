package com.learnthink.core.agent.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.framework.*;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import com.learnthink.core.config.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
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
 *   <li>Returns chat reply first (non-blocking), then async triggers ProfileAnalyzer</li>
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

        // Step 1: Generate reply with optional tool (ReAct)
        String systemPrompt = input.systemPromptOverride() != null && !input.systemPromptOverride().isBlank()
            ? input.systemPromptOverride()
            : promptLoader.get("agent/conversation");
        List<Message> messages = buildMessages(systemPrompt, input.conversationHistory());

        var promptSpec = chatClient.prompt().messages(messages);
        ToolCallback ragToolCallback = ctx.get("rag_tool");
        if (ragToolCallback != null) {
            promptSpec = promptSpec.toolCallbacks(ragToolCallback);
        }
        String reply = promptSpec.call().content();

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
     * Stream the LLM reply token-by-token with optional tool access.
     * Emits {@link ChatResponse} objects so the caller can observe tool calls
     * and emit intermediate SSE events (RETRIEVE/RAG thought events).
     * Does NOT evaluate sufficiency — that happens post-stream in ChatServiceImpl.
     */
    public Flux<ChatResponse> streamReply(ConversationInput input, AgentContext ctx) {
        String systemPrompt = input.systemPromptOverride() != null && !input.systemPromptOverride().isBlank()
            ? input.systemPromptOverride()
            : promptLoader.get("agent/conversation");
        List<Message> messages = buildMessages(systemPrompt, input.conversationHistory());

        long start = System.currentTimeMillis();
        ctx.observation().onPrompt(name(), "Streaming reply generation (round " + input.roundNumber() + ")",
            Map.of("courseId", input.courseId(), "historySize", input.conversationHistory().size()));

        var promptSpec = chatClient.prompt().messages(messages);
        ToolCallback ragToolCallback = ctx.get("rag_tool");
        if (ragToolCallback != null) {
            promptSpec = promptSpec.toolCallbacks(ragToolCallback);
        }
        return promptSpec.stream().chatResponse()
            .doFinally(signalType -> {
                long elapsed = System.currentTimeMillis() - start;
                ctx.observation().onResponse(name(),
                    "Stream complete (" + elapsed + "ms)", elapsed, AgentResult.TokenUsage.ZERO);
            });
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

            // Parse structured JSON — strip markdown code fences robustly
            String json = response;
            int fenceStart = json.indexOf("```");
            if (fenceStart >= 0) {
                // Skip the opening ``` and optional language tag (e.g. ```json, ```python)
                int contentStart = json.indexOf('\n', fenceStart);
                if (contentStart < 0) contentStart = fenceStart + 3;
                else contentStart = contentStart + 1;
                int fenceEnd = json.lastIndexOf("```");
                if (fenceEnd > contentStart) {
                    json = json.substring(contentStart, fenceEnd);
                }
            }
            json = json.trim();
            // If response is wrapped in text, isolate the JSON object
            int braceStart = json.indexOf('{');
            int braceEnd = json.lastIndexOf('}');
            if (braceStart >= 0 && braceEnd > braceStart) {
                json = json.substring(braceStart, braceEnd + 1);
            }

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
    // Resource generation intent detection (v4.0 — LLM-based)
    // ================================================================

    /**
     * LLM determines IF user wants generation (boolean), then keyword extraction
     * handles types/focus/quantity for precision.
     */
    public GenerationIntent detectGenerationIntent(String userMessage,
                                                    List<Map<String, String>> conversationHistory) {
        if (userMessage == null || userMessage.isBlank()) return null;

        // Build conversation context for LLM decision
        StringBuilder context = new StringBuilder();
        if (conversationHistory != null) {
            int start = Math.max(0, conversationHistory.size() - 6);
            for (int i = start; i < conversationHistory.size(); i++) {
                var msg = conversationHistory.get(i);
                String role = msg.getOrDefault("role", "");
                String content = msg.getOrDefault("content", "");
                if ("user".equals(role)) context.append("学生: ").append(content).append("\n");
                else if ("assistant".equals(role)) context.append("老师: ").append(content).append("\n");
            }
        }

        String prompt = """
            判断学生是否在表示想要生成学习资源。只需返回 true 或 false。
            true 的例子：说"生成资料"、"做练习题"、"帮我创建"、"全部"、回应老师建议时说"好/行/可以/嗯/对/开始"
            false 的例子：问知识点、拒绝（含不/不用）、日常聊天、回答画像问题
            只输出 true 或 false，不要其他内容。
            """;

        try {
            String response = chatClient.prompt()
                .messages(
                    new SystemMessage(prompt),
                    new UserMessage(context.length() > 0 ? context.toString() : userMessage))
                .call().content();

            boolean wantsGen = response != null && (response.trim().equals("true") ||
                response.trim().toLowerCase().startsWith("true"));

            if (!wantsGen) return null;

            // LLM says YES — use keyword extraction for precise types/focus/quantity
            var prefs = keywordExtractPreferences(userMessage);

            log.info("GenIntent detected (LLM=true), prefs={}", prefs);
            return new GenerationIntent(true, prefs);

        } catch (Exception e) {
            log.warn("LLM intent detection failed, using keyword fallback: {}", e.getMessage());
            GenerationIntent fallback = keywordDetectGenerationIntent(userMessage);
            if (fallback != null) log.info("GenIntent detected (keyword fallback), prefs={}", fallback.preferences());
            return fallback;
        }
    }

    /** Keyword-based fallback for when LLM call fails */
    private GenerationIntent keywordDetectGenerationIntent(String userMessage) {
        String lower = userMessage.toLowerCase().trim();
        if (lower.startsWith("确认生成") && lower.contains("「")) return null;

        boolean hasPositive = lower.contains("生成") || lower.contains("创建") ||
            lower.contains("做") || lower.contains("要") || lower.contains("可以") ||
            lower.contains("好") || lower.contains("行") || lower.contains("嗯") ||
            lower.contains("对") || lower.contains("是") || lower.contains("开始") ||
            lower.contains("来") || lower.contains("帮") || lower.contains("给") ||
            lower.contains("全部") || lower.contains("所有");

        boolean hasNegative = lower.contains("不") || lower.contains("不要") ||
            lower.contains("不用") || lower.contains("算了") || lower.contains("等等") ||
            lower.contains("先不") || lower.contains("暂") || lower.contains("再说");

        if (!hasPositive || hasNegative) return null;

        var prefs = keywordExtractPreferences(lower);
        return new GenerationIntent(true, prefs);
    }

    /** Keyword-based preference extraction fallback */
    private Map<String, Object> keywordExtractPreferences(String message) {
        Map<String, Object> prefs = new java.util.LinkedHashMap<>();
        java.util.List<String> requestedTypes = new java.util.ArrayList<>();
        if (message.contains("文档") || message.contains("讲解") || message.contains("讲义")) requestedTypes.add("doc");
        if (message.contains("题") || message.contains("练习") || message.contains("习题")) requestedTypes.add("quiz");
        if (message.contains("导图") || message.contains("思维导图") || message.contains("脑图")) requestedTypes.add("mindmap");
        if (message.contains("代码") || message.contains("编程") || message.contains("实操")) requestedTypes.add("code");
        if (message.contains("阅读") || message.contains("拓展") || message.contains("资料")) requestedTypes.add("reading");
        if (!requestedTypes.isEmpty()) prefs.put("requestedTypes", requestedTypes);
        if (message.contains("基础") || message.contains("入门")) prefs.put("focus", "foundation");
        if (message.contains("进阶") || message.contains("深入") || message.contains("高级")) prefs.put("focus", "advanced");
        if (message.contains("考试") || message.contains("复习") || message.contains("应试")) prefs.put("focus", "exam");
        if (message.contains("全部") || message.contains("所有") || message.contains("5") || message.contains("五")) {
            prefs.put("quantity", "all");
            if (requestedTypes.isEmpty()) {
                requestedTypes.addAll(java.util.List.of("doc", "quiz", "mindmap", "code", "reading"));
                prefs.put("requestedTypes", requestedTypes);
            }
            if (!prefs.containsKey("focus")) prefs.put("focus", "all");
        } else if (message.contains("先") || message.contains("一个") || message.contains("一种") || message.contains("试试")) {
            prefs.put("quantity", "one");
        }
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
     * @param courseName course display name (may be null if unknown)
     */
    public String generateClarifyingQuestion(GenerationIntent intent, SufficiencyResult sufficiency,
                                              String courseName) {
        var prefs = intent.preferences();
        boolean hasTypes = prefs.containsKey("requestedTypes");
        String context = (courseName != null && !courseName.isBlank()) ? "针对《" + courseName + "》，" : "";

        if (!hasTypes) {
            return "好的！" + context + "你想生成哪些类型的资源呢？\n\n"
                + "1. 📄 讲解文档 — 系统学习知识点\n"
                + "2. 📝 练习题 — 检验掌握程度\n"
                + "3. 🧠 思维导图 — 梳理知识脉络\n"
                + "4. 💻 代码实操 — 动手实践\n"
                + "5. 📚 拓展阅读 — 了解前沿进展\n\n"
                + "你可以选几项，或者说\"全部\"～";
        }

        // Types specified → ready to confirm
        return null; // null means "ready to trigger"
    }

    // ================================================================
    // Topic resolution — intent → concrete topic
    // ================================================================

    /**
     * Resolve user's vague generation intent into a specific, retrievable topic.
     * Uses conversation history + course name + profile dimensions to determine
     * the actual knowledge topic (not the user's verbatim phrase).
     */
    public String resolveTopic(String courseName,
                                List<Map<String, String>> conversationHistory,
                                Map<String, Object> profileDimensions) {
        // Build conversation summary (last few exchanges, if any)
        boolean hasConversation = conversationHistory != null && conversationHistory.size() >= 2;
        String convo;
        if (hasConversation) {
            StringBuilder sb = new StringBuilder();
            int start = Math.max(0, conversationHistory.size() - 6);
            for (int i = start; i < conversationHistory.size(); i++) {
                var m = conversationHistory.get(i);
                sb.append(m.getOrDefault("role", "")).append(": ")
                    .append(m.getOrDefault("content", "")).append("\n");
            }
            convo = "Recent conversation:\n" + sb.toString();
        } else {
            convo = "The student requested resource generation for their course: " + courseName;
        }

        // Handle empty conversation: skip LLM call and use course-based fallback
        if (!hasConversation && (profileDimensions == null || profileDimensions.isEmpty())) {
            log.info("Topic resolution skipped (new chat, no profile) → defaulting to course name");
            return courseName + "核心知识点";
        }

        String profileInfo = "";
        if (profileDimensions != null && !profileDimensions.isEmpty()) {
            // Extract key profile fields
            StringBuilder pi = new StringBuilder("Profile: ");
            if (profileDimensions.containsKey("weak_top")) pi.append("weak areas: ").append(profileDimensions.get("weak_top")).append("; ");
            if (profileDimensions.containsKey("goal")) pi.append("goal: ").append(profileDimensions.get("goal")).append("; ");
            if (profileDimensions.containsKey("current_chapter")) pi.append("chapter: ").append(profileDimensions.get("current_chapter")).append("; ");
            profileInfo = pi.toString();
        }

        // Skip LLM call if no conversation AND no profile — use course name directly
        if (!hasConversation && profileInfo.isEmpty()) {
            log.info("No conversation or profile data → default topic: {}核心知识点", courseName);
            return courseName + "核心知识点";
        }

        String prompt = String.format("""
            The student is taking course: %s

            %s
            %s

            The student wants to generate learning resources. Determine the MOST SPECIFIC
            topic that should be generated:
            - Output ONLY the topic (2-20 Chinese characters)
            - Be as specific as possible (concept or chapter, not course name)
            - Never output meta-words like "学习资源", "该课程", "资源", "课程"
            - If no clear topic can be determined, output the most likely chapter topic for this course
            Topic:""",
            courseName, convo, profileInfo);

        try {
            String topic = chatClient.prompt()
                .messages(new SystemMessage(
                    "Extract the specific knowledge topic the student wants to study. Output topic only, no explanation."),
                    new UserMessage(prompt))
                .call().content();
            topic = topic != null ? topic.trim() : "";
            if (topic.length() > 40) topic = topic.substring(0, 40);
            if (topic.isEmpty() || topic.contains("学习资源") || topic.contains("该课程")) {
                return courseName + "核心知识点";
            }
            log.info("Topic resolved → \"{}\"", topic);
            return topic;
        } catch (Exception e) {
            log.warn("Topic resolution failed: {}", e.getMessage());
            return courseName + "核心知识点";
        }
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
