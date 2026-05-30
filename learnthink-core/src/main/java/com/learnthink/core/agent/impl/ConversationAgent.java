package com.learnthink.core.agent.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.*;
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
 * 用于画像构建对话的对话智能体。
 *
 * <h3>设计（来自 05-画像对话 v3.0）：</h3>
 * <ul>
 *   <li>L3 判断级自主性：决定何时已收集足够信息</li>
 *   <li>通过结构化置信度评分进行智能充足性评估（而非字符串匹配）</li>
 *   <li>先返回聊天回复（非阻塞），然后异步触发 ProfileAnalyzer</li>
 *   <li>每次对话轮次执行 Plan-Act-Observe-Reflect 循环</li>
 * </ul>
 *
 * <h3>与 ChatServiceImpl 的关键区别：</h3>
 * <p>ChatServiceImpl 是直接调用 ChatClient 的独立服务。
 * ConversationAgent 是运行在 StateGraph 引擎上的智能体，与其他智能体共享 AgentContext，
 * 并通过 Observation 推送思维链事件。</p>
 */
@Component
public class ConversationAgent {

    private static final Logger log = LoggerFactory.getLogger(ConversationAgent.class);
    private final ChatClient chatClient;
    private final PromptLoader promptLoader;
    private final ObjectMapper mapper = new ObjectMapper();

    public ConversationAgent(@Qualifier("chatChatClientBuilder") ChatClient.Builder chatClientBuilder,
                             PromptLoader promptLoader) {
        this.chatClient = chatClientBuilder.build();
        this.promptLoader = promptLoader;
    }

    public String name() { return "ConversationAgent"; }

    public boolean isRetryable() { return false; }

    // ================================================================
    // 计划-执行-观察-反思循环（每次对话轮次）
    // ================================================================

    public AgentResult<ConversationOutput> execute(ConversationInput input, AgentContext ctx) {
        Instant start = Instant.now();

        // === 计划 ===
        ctx.observation().onPrompt(name(),
            "评估对话状态（第 " + input.roundNumber() + " 轮）",
            Map.of("courseId", input.courseId(), "historySize", input.conversationHistory().size()));

        // 步骤1：生成回复并可选使用工具（ReAct）
        String systemPrompt = input.systemPromptOverride() != null && !input.systemPromptOverride().isBlank()
            ? input.systemPromptOverride()
            : promptLoader.get("agent/conversation");
        List<Message> messages = buildMessages(systemPrompt, input.conversationHistory());

        var promptSpec = chatClient.prompt().messages(messages);
        ToolCallback ragToolCallback = ctx.get("rag_tool");
        if (ragToolCallback != null) {
            promptSpec = promptSpec.toolCallbacks(ragToolCallback);
        }
        ToolCallback bookInfoToolCallback = ctx.get("book_info_tool");
        if (bookInfoToolCallback != null) {
            promptSpec = promptSpec.toolCallbacks(bookInfoToolCallback);
        }
        String reply = promptSpec.call().content();
        log.info("[AI-RESPONSE][ConversationAgent] execute reply length={} chars\n{}",
            reply != null ? reply.length() : 0,
            reply != null ? reply.substring(0, Math.min(2000, reply.length())) : "null");

        // === 执行 ===
        ctx.observation().onResponse(name(), "已生成回复（" + reply.length() + " 字）",
            java.time.Duration.between(start, Instant.now()).toMillis(), AgentResult.TokenUsage.ZERO);

        // 步骤2：评估充足性（结构化置信度评分）
        SufficiencyResult sufficiency = evaluateSufficiency(input.conversationHistory());

        // === 观察 ===
        ctx.observation().onDecision(name(),
            sufficiency.sufficient() ? "SUFFICIENT" : "CONTINUE",
            "已覆盖 " + sufficiency.coveredCount() + "/7 个维度，置信度=" + sufficiency.overallConfidence());

        // 存储到上下文中供 OrchestratorAgent 使用
        ctx.put("profile.sufficient", sufficiency.sufficient());
        ctx.put("profile.covered_count", sufficiency.coveredCount());
        ctx.put("profile.confidence", sufficiency.overallConfidence());
        ctx.put("profile.missing_dimensions", sufficiency.missingDimensions());

        // === 反思 ===
        // （L3 判断：智能体自身决定是否结束对话）
        // 反思已捕获在上述观察中

        long elapsed = java.time.Duration.between(start, Instant.now()).toMillis();
        ConversationOutput output = new ConversationOutput(reply, sufficiency);
        return AgentResult.of(output, AgentResult.TokenUsage.ZERO, elapsed,
            Map.of("agent", name(), "sufficient", sufficiency.sufficient(),
                   "coveredCount", sufficiency.coveredCount()));
    }

    // ================================================================
    // 流式回复（真正的流式，返回 Flux<String>）
    // ================================================================

    /**
     * 逐令牌流式传输 LLM 回复，支持可选的工具访问。
     * 发射 {@link ChatResponse} 对象，以便调用者可以观察工具调用
     * 并发出中间 SSE 事件（RETRIEVE/RAG 思考事件）。
     * 不评估充足性——这会在 ChatServiceImpl 中的流结束后进行。
     */
    public Flux<ChatResponse> streamReply(ConversationInput input, AgentContext ctx) {
        String systemPrompt = input.systemPromptOverride() != null && !input.systemPromptOverride().isBlank()
            ? input.systemPromptOverride()
            : promptLoader.get("agent/conversation");
        List<Message> messages = buildMessages(systemPrompt, input.conversationHistory());

        long start = System.currentTimeMillis();
        ctx.observation().onPrompt(name(), "流式生成回复（第 " + input.roundNumber() + " 轮）",
            Map.of("courseId", input.courseId(), "historySize", input.conversationHistory().size()));

        var promptSpec = chatClient.prompt().messages(messages);
        ToolCallback ragToolCallback = ctx.get("rag_tool");
        if (ragToolCallback != null) {
            promptSpec = promptSpec.toolCallbacks(ragToolCallback);
        }
        ToolCallback bookInfoToolCallback = ctx.get("book_info_tool");
        if (bookInfoToolCallback != null) {
            promptSpec = promptSpec.toolCallbacks(bookInfoToolCallback);
        }
        return promptSpec.stream().chatResponse()
            .doFinally(signalType -> {
                long elapsed = System.currentTimeMillis() - start;
                ctx.observation().onResponse(name(),
                    "流式回复完成（" + elapsed + "ms）", elapsed, AgentResult.TokenUsage.ZERO);
            });
    }

    // ================================================================
    // 充足性评估（结构化置信度评分）
    // 替换脆弱的 [PROFILE_READY] 字符串标记方法
    // ================================================================

    public SufficiencyResult evaluateSufficiency(List<Map<String, String>> conversationHistory) {
        String evalPrompt = """
            评估对话对学生画像的覆盖程度。
            对每个维度评分 0-1：
              - 0：完全未提及
              - 0.3-0.5：间接推断
              - 0.6-0.8：明确提及但较模糊
              - 0.9-1.0：明确且清晰地陈述

            维度：
            1. major_context — 专业、课程、当前章节
            2. knowledge_basis — 知识薄弱点与优势
            3. learning_goal — 学习目标、截止日期、子目标
            4. cognitive_style — 偏好的学习方式、应避免的内容
            5. learning_pace — 每天分钟数、每周天数、紧迫程度
            6. interest_direction — 感兴趣的话题与应用方向
            7. error_pattern — 常见错误类型

            输出严格的 JSON：
            {
              "dimensions": {
                "major_context": {"score": 0.9, "evidence": "简短引用"},
                ...
              },
              "covered_count": 4,
              "overall_sufficient": false,
              "missing_dimensions": ["learning_pace", "interest_direction"],
              "suggested_question": "..."
            }

            规则：sufficient = covered_count >= 4 且所有已覆盖维度评分 >= 0.7
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

            log.info("[AI-RESPONSE][ConversationAgent] evaluateSufficiency length={} chars\n{}",
                response != null ? response.length() : 0,
                response != null ? response.substring(0, Math.min(1500, response.length())) : "null");

            // LLM 输出可能包裹 markdown 代码围栏，需鲁棒去除
            String json = response;
            int fenceStart = json.indexOf("```");
            if (fenceStart >= 0) {
                // 跳过开头的 ``` 及可选的语言标签（如 json、python）
                int contentStart = json.indexOf('\n', fenceStart);
                if (contentStart < 0) contentStart = fenceStart + 3;
                else contentStart = contentStart + 1;
                int fenceEnd = json.lastIndexOf("```");
                if (fenceEnd > contentStart) {
                    json = json.substring(contentStart, fenceEnd);
                }
            }
            json = json.trim();
            // 若响应嵌在文本中，仅提取 JSON 对象
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
            log.warn("充足性评估失败，默认继续对话：{}", e.getMessage());
            return new SufficiencyResult(false, 0, 0, List.of(), "");
        }
    }

    // ================================================================
    // 资源生成功能检测（v4.0 — 基于 LLM）
    // ================================================================

    /**
     * LLM 判断用户是否想要生成资源（布尔值），然后关键词提取
     * 处理类型/重点/数量以提高精度。
     */
    public GenerationIntent detectGenerationIntent(String userMessage,
                                                    List<Map<String, String>> conversationHistory) {
        if (userMessage == null || userMessage.isBlank()) return null;

        // 构建对话上下文供 LLM 决策
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

            log.info("[AI-RESPONSE][ConversationAgent] detectGenerationIntent: {}",
                response != null ? response.trim() : "null");

            boolean wantsGen = response != null && (response.trim().equals("true") ||
                response.trim().toLowerCase().startsWith("true"));

            if (!wantsGen) return null;

            // LLM 说 YES —— 使用关键词提取获取精确的类型/重点/数量
            var prefs = keywordExtractPreferences(userMessage);

            log.info("检测到生成意图（LLM=true），偏好={}", prefs);
            return new GenerationIntent(true, prefs);

        } catch (Exception e) {
            log.warn("LLM 意图检测失败，使用关键词后备方案：{}", e.getMessage());
            GenerationIntent fallback = keywordDetectGenerationIntent(userMessage);
            if (fallback != null) log.info("检测到生成意图（关键词后备），偏好={}", fallback.preferences());
            return fallback;
        }
    }

    /** LLM 调用失败时的关键词后备方案 */
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

    /** 基于关键词的偏好提取后备方案 */
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
     * 根据学生的画像生成自然的资源创建邀请。
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
     * 当用户想要生成但未指定需求时，生成澄清问题。
     * @param courseName 课程显示名称（如果未知可能为 null）
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

        // 类型已指定 → 准备确认
        return null; // null 表示"可以触发生成"
    }

    // ================================================================
    // 主题解析 — 意图 → 具体主题
    // ================================================================

    /**
     * 将用户模糊的生成功能解析为具体的、可检索的主题。
     * 使用对话历史 + 课程名称 + 画像维度来确定
     * 实际的知识主题（而不是用户的原话）。
     */
    public String resolveTopic(String courseName,
                                List<Map<String, String>> conversationHistory,
                                Map<String, Object> profileDimensions) {
        // 构建对话摘要（最近的几次交流，如果有的话）
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
            convo = "最近对话：\n" + sb.toString();
        } else {
            convo = "学生请求为课程生成资源：" + courseName;
        }

        // 如果没有对话且没有画像，跳过 LLM 调用并使用基于课程的后备方案
        if (!hasConversation && (profileDimensions == null || profileDimensions.isEmpty())) {
            log.info("跳过主题解析（新对话，无画像）→ 默认使用课程名称");
            return courseName + "核心知识点";
        }

        String profileInfo = "";
        if (profileDimensions != null && !profileDimensions.isEmpty()) {
            // 提取关键画像字段
            StringBuilder pi = new StringBuilder("画像：");
            if (profileDimensions.containsKey("weak_top")) pi.append("薄弱点：").append(profileDimensions.get("weak_top")).append("；");
            if (profileDimensions.containsKey("goal")) pi.append("目标：").append(profileDimensions.get("goal")).append("；");
            if (profileDimensions.containsKey("current_chapter")) pi.append("章节：").append(profileDimensions.get("current_chapter")).append("；");
            profileInfo = pi.toString();
        }

        // 如果没有对话且没有画像，跳过 LLM 调用——直接使用课程名称
        if (!hasConversation && profileInfo.isEmpty()) {
            log.info("无对话或画像数据 → 默认主题：{}核心知识点", courseName);
            return courseName + "核心知识点";
        }

        String prompt = String.format("""
            学生正在学习课程：%s

            %s
            %s

            学生想要生成学习资源。确定应生成的最具体主题：
            - 仅输出主题（2-20 个汉字）
            - 尽可能具体（概念或章节，而非课程名称）
            - 切勿输出"学习资源""该课程""资源""课程"等元词汇
            - 若无法确定明确主题，输出该课程最可能的章节主题
            主题：""",
            courseName, convo, profileInfo);

        try {
            String topic = chatClient.prompt()
                .messages(new SystemMessage(
                    "提取学生想要学习的特定知识主题。仅输出主题，无需解释。"),
                    new UserMessage(prompt))
                .call().content();
            log.info("[AI-RESPONSE][ConversationAgent] resolveTopic raw: {}",
                topic != null ? topic.trim() : "null");
            topic = topic != null ? topic.trim() : "";
            if (topic.length() > 40) topic = topic.substring(0, 40);
            if (topic.isEmpty() || topic.contains("学习资源") || topic.contains("该课程")) {
                return courseName + "核心知识点";
            }
            log.info("主题解析完成 → \"{}\"", topic);
            return topic;
        } catch (Exception e) {
            log.warn("主题解析失败：{}", e.getMessage());
            return courseName + "核心知识点";
        }
    }

    // ================================================================
    // 数据类型
    // ================================================================

    public record GenerationIntent(boolean wantsGeneration, Map<String, Object> preferences) {}

    // ================================================================
    // 辅助方法
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
    // 数据类型
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
