package com.learnthink.core.smart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.agent.runtime.AgentTool;
import com.learnthink.core.agent.tools.ToolContext;
import com.learnthink.core.agent.tools.ToolRegistry;
import com.learnthink.core.agent.tools.callbacks.ToolCallbackFactory;
import com.learnthink.core.agent.tools.visual.SmartSvgTool;
import com.learnthink.core.agent.tools.visual.GenerateChartTool;
import com.learnthink.core.agent.tools.visual.GenerateMermaidTool;
import com.learnthink.core.agent.tools.visual.GenerateHtmlTool;
import com.learnthink.core.agent.tools.visual.GenerateVisualizationTool;
import com.learnthink.core.agent.tools.visual.GenerateThreeJsTool;
import com.learnthink.core.agent.tools.visual.GenerateMindmapTool;
import com.learnthink.core.config.TutoringConfig;
import com.learnthink.core.domain.entity.ChatMessage;
import com.learnthink.core.domain.entity.ChatSession;
import com.learnthink.core.repository.ChatMessageMapper;
import com.learnthink.core.repository.ChatSessionMapper;
import com.learnthink.core.service.ProfileService;
import com.learnthink.core.service.chat.ChatMessageService;
import com.learnthink.core.smart.domain.*;
import com.learnthink.core.smart.event.SmartEventEmitter;
import com.learnthink.core.smart.event.SseEvent;
import com.learnthink.core.smart.event.ToolCallObserver;
import com.learnthink.core.smart.store.SmartStateStore;
import com.learnthink.core.smart.tools.*;
import com.learnthink.core.tutoring.domain.QuestionAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Smart 模式核心循环。
 *
 * <h3>设计要点</h3>
 * <ol>
 *   <li>利用 Spring AI 的 toolCallbacks 机制，LLM 在流式输出中自主调用工具</li>
 *   <li>工具调用前后的 thinking 事件通过 ToolCallObserver 桥接到 SSE</li>
 *   <li>工具产出（SVG/Chart/Mermaid/HTML）通过 agent.visual 事件推送</li>
 *   <li>文字输出通过 chunk 事件流式推送</li>
 *   <li>所有内容在一条消息流中自然交替</li>
 * </ol>
 *
 * <h3>与 v1 的核心区别</h3>
 * <ul>
 *   <li>v1：代码控制循环，每步选动作->执行->观察->下一步</li>
 *   <li>v2：LLM 控制循环，Spring AI 的 toolCallbacks 自动处理 Thought->Action->Observation。
 *       代码只负责：注入工具、桥接 SSE、安全兜底</li>
 * </ul>
 */
@Component
public class SmartAgentLoop {
    private static final Logger log = LoggerFactory.getLogger(SmartAgentLoop.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient.Builder chatClientBuilder;
    private final ToolRegistry toolRegistry;
    private final ToolCallbackFactory callbackFactory;
    private final SmartToolComposition toolComposition;
    private final SmartStateStore stateStore;
    private final SmartSafetyGuard safetyGuard;
    private final TutoringConfig config;
    private final ProfileService profileService;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageService chatMessageService;

    // 可视化工具（从 Bean 容器注入）
    private final SmartSvgTool smartSvgTool;
    private final GenerateChartTool generateChartTool;
    private final GenerateMermaidTool generateMermaidTool;
    private final GenerateHtmlTool generateHtmlTool;
    private final GenerateVisualizationTool generateVisualizationTool;
    private final GenerateThreeJsTool generateThreeJsTool;
    private final GenerateMindmapTool generateMindmapTool;

    public SmartAgentLoop(
            @Qualifier("smartChatClientBuilder") ChatClient.Builder chatClientBuilder,
            ToolRegistry toolRegistry,
            ToolCallbackFactory callbackFactory,
            SmartToolComposition toolComposition,
            SmartStateStore stateStore,
            SmartSafetyGuard safetyGuard,
            TutoringConfig config,
            ProfileService profileService,
            ChatMessageMapper chatMessageMapper,
            ChatSessionMapper chatSessionMapper,
            ChatMessageService chatMessageService,
            org.springframework.beans.factory.ObjectProvider<SmartSvgTool> smartSvgToolProvider,
            org.springframework.beans.factory.ObjectProvider<GenerateChartTool> generateChartToolProvider,
            org.springframework.beans.factory.ObjectProvider<GenerateMermaidTool> generateMermaidToolProvider,
            org.springframework.beans.factory.ObjectProvider<GenerateHtmlTool> generateHtmlToolProvider,
            org.springframework.beans.factory.ObjectProvider<GenerateVisualizationTool> generateVisualizationToolProvider,
            org.springframework.beans.factory.ObjectProvider<GenerateThreeJsTool> generateThreeJsToolProvider,
            org.springframework.beans.factory.ObjectProvider<GenerateMindmapTool> generateMindmapToolProvider) {
        this.chatClientBuilder = chatClientBuilder;
        this.toolRegistry = toolRegistry;
        this.callbackFactory = callbackFactory;
        this.toolComposition = toolComposition;
        this.stateStore = stateStore;
        this.safetyGuard = safetyGuard;
        this.config = config;
        this.profileService = profileService;
        this.chatMessageMapper = chatMessageMapper;
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageService = chatMessageService;
        this.smartSvgTool = smartSvgToolProvider.getIfAvailable();
        this.generateChartTool = generateChartToolProvider.getIfAvailable();
        this.generateMermaidTool = generateMermaidToolProvider.getIfAvailable();
        this.generateHtmlTool = generateHtmlToolProvider.getIfAvailable();
        this.generateVisualizationTool = generateVisualizationToolProvider.getIfAvailable();
        this.generateThreeJsTool = generateThreeJsToolProvider.getIfAvailable();
        this.generateMindmapTool = generateMindmapToolProvider.getIfAvailable();
    }

    // ================================================================
    // 公开入口
    // ================================================================

    /**
     * 首次调用：概念拆解 -> 初始化上下文 -> 启动 ReAct 循环。
     */
    public void execute(SmartStartRequest request, SseEmitter sse) {
        SmartEventEmitter emitter = new SmartEventEmitter(sse);
        String sessionId = request.sessionId() != null && !request.sessionId().isBlank()
            ? request.sessionId() : UUID.randomUUID().toString();
        String userId = UserContextUtil.getCurrentUserId();

        try {
            // 1. 概念拆解
            emitter.thought("PLANNING", "正在分析问题，拆解子概念...");
            List<ConceptBreakdown> concepts = generateConceptBreakdown(request.question(), request.courseId());
            QuestionAnalysis analysis = analyzeQuestion(request.question());

            // 2. 初始化 SmartContext
            String profileSummary = buildProfileSummary(userId, request.courseId());
            ConvergenceGoal goal = ConvergenceGoal.defaults(
                concepts.stream().map(ConceptBreakdown::id).toList(),
                config.getSmart().getMaxInteractions(),
                config.getSmart().getMinInteractions());
            SmartContext ctx = SmartContext.initialize(
                sessionId, userId, request.courseId(),
                concepts, profileSummary, goal);
            stateStore.save(sessionId, ctx);

            // 3. 通知前端：smart 模式开始
            emitter.smartStarted(sessionId, analysis, concepts);
            emitter.thought("PLANNING",
                "问题分析完成：" + analysis.questionType() + "领域，难度" + analysis.difficulty()
                + "。拆解出 " + concepts.size() + " 个子概念："
                + concepts.stream().map(ConceptBreakdown::label).reduce((a, b) -> a + "、" + b).orElse("无"));

            // 4. 保存用户消息
            String chatId = request.chatId() != null ? request.chatId() : sessionId;
            ensureChatSession(chatId, userId, request.courseId(), request.question());
            saveUserMessage(chatId, userId, request.question());

            // 5. 启动 ReAct 循环
            runReActLoop(request.question(), ctx, chatId, emitter);

        } catch (Exception e) {
            log.error("Smart tutoring failed: {}", e.getMessage(), e);
            emitter.error("SMART_INIT_FAILED", e.getMessage(), true);
        }
    }

    /**
     * 学生回答后恢复循环。
     */
    public void resume(SmartAnswerRequest request, SseEmitter sse) {
        SmartEventEmitter emitter = new SmartEventEmitter(sse);
        log.info("[SMART-DIAG] === resume() called === sessionId={}, chatId={}, answerLen={}",
            request.sessionId(), request.chatId(),
            request.answer() != null ? request.answer().length() : 0);

        try {
            SmartContext ctx = stateStore.load(request.sessionId());
            if (ctx == null) {
                log.warn("[SMART-DIAG] resume: stateStore.load returned null for sessionId={}", request.sessionId());
                emitter.error("SMART_STATE_NOT_FOUND",
                    "智能辅导状态不存在或已过期，请重新开始", false);
                return;
            }
            log.info("[SMART-DIAG] resume: context loaded, totalTurns={}, concepts={}",
                ctx.totalTurns(), ctx.concepts() != null ? ctx.concepts().size() : 0);

            // 保存用户消息
            String chatId = request.chatId() != null ? request.chatId() : request.sessionId();
            saveUserMessage(chatId, ctx.userId(), request.answer());

            // 发出初始思考事件（理解学生输入的上下文）
            emitter.thought("CONTEXT", "正在理解你的问题...");

            // 学生输入直接作为新一轮对话，启动 ReAct 循环
            runReActLoop(request.answer(), ctx, chatId, emitter);

        } catch (Exception e) {
            log.error("Smart resume failed: {}", e.getMessage(), e);
            emitter.error("SMART_RESUME_FAILED", e.getMessage(), true);
        }
    }

    // ================================================================
    // ReAct 循环核心
    // ================================================================

    /**
     * ReAct 循环核心。
     *
     * <p>Spring AI 的 toolCallbacks 机制自动处理多轮工具调用：
     * <ul>
     *   <li>LLM 输出文字（流式推送 chunk）</li>
     *   <li>LLM 决定调用工具（Spring AI 自动执行，返回结果给 LLM）</li>
     *   <li>LLM 基于工具结果继续输出文字</li>
     *   <li>循环直到 LLM 不再调用工具，输出完成</li>
     * </ul>
     *
     * <p>我们的职责：
     * <ul>
     *   <li>注入所有可用工具（含 per-session 状态工具，传入 ctxRef）</li>
     *   <li>桥接思考事件和工具事件到 SSE</li>
     *   <li>在流结束后更新 SmartContext</li>
     *   <li>检查收敛</li>
     * </ul>
     */
    private void runReActLoop(String userMessage, SmartContext initialCtx,
                               String chatId, SmartEventEmitter emitter) {
        // ★ 收敛检查前置
        if (initialCtx.goal() != null && initialCtx.goal().isHardConverged(initialCtx)
            || initialCtx.converged()) {
            emitter.smartConverged(initialCtx.convergenceSummary());
            emitter.done(initialCtx);
            return;
        }

        // ★ 统一状态持有者：工具和 doFinally 都通过 ctxRef 读写
        AtomicReference<SmartContext> ctxRef = new AtomicReference<>(initialCtx);

        // 1. 组装工具（先于系统提示词，确保提示词只列出实际可用的工具）
        ToolContext toolCtx = ToolContext.builder()
            .userToggledTools(List.of("web_search", "reason", "brainstorm"))
            .hasCourse(true)
            .hasProfile(true)
            .hasJudge0(config.getSmart().isEnableCodeExecution())
            .hasLearningPath(true)
            .build();
        List<String> toolNames = toolComposition.composeSmartTools(toolCtx, initialCtx);
        List<ToolCallback> toolCallbacks = resolveToolCallbacks(toolNames, ctxRef, initialCtx, emitter);

        // 2. 构建系统提示词（基于实际可用的 toolNames 动态生成工具列表）
        String systemPrompt = buildSystemPrompt(initialCtx, toolNames);

        // 3. 构建对话历史
        List<Message> messages = buildMessages(systemPrompt, initialCtx, chatId, userMessage);

        // 4. 注入工具调用观察者（桥接 SSE + 分离 observation + 工具调用次数限制）
        List<SseEvent> toolEventBuffer = Collections.synchronizedList(new ArrayList<>());
        ToolCallObserver observer = new ToolCallObserver(
            toolEventBuffer, config.getSmart().getMaxToolCallsPerTurn());
        toolCallbacks = observer.wrap(toolCallbacks);

        // 5. 流式调用 LLM（Spring AI 自动处理多轮工具调用）
        ChatClient chatClient = chatClientBuilder.build();
        StringBuilder replyBuffer = new StringBuilder();
        // 持久化用：LLM 原生思维链（reasoning_content）步骤
        List<Map<String, Object>> persistedReasoningSteps =
            Collections.synchronizedList(new ArrayList<>());
        // 持久化用：按事件实际顺序收集的内容块（文字和可视化交替）
        List<Map<String, Object>> persistedBlocks =
            Collections.synchronizedList(new ArrayList<>());

        log.info("SmartAgentLoop: starting ReAct loop, sessionId={}, tools={}",
            initialCtx.sessionId(), toolNames);

        chatClient.prompt()
            .messages(messages)
            .toolCallbacks(toolCallbacks.toArray(new ToolCallback[0]))
            .stream()
            .chatResponse()
            .flatMap(chatResponse -> {
                // ★ 统一用 flatMap 处理：先 flush 工具事件，再处理 LLM 输出
                List<SseEvent> events = new ArrayList<>();
                synchronized (toolEventBuffer) {
                    events.addAll(toolEventBuffer);
                    toolEventBuffer.clear();
                }

                // 检查工具调用次数
                if (safetyGuard.tooManyToolCalls(observer.getToolsUsed())) {
                    log.warn("Tool call limit exceeded: {}", observer.getToolsUsed().size());
                    // 不抛异常，让 LLM 继续完成当前输出
                }

                // 常规文本输出
                if (chatResponse.getResult() != null) {
                    var output = chatResponse.getResult().getOutput();

                    // ★ 捕获 reasoning_content（DeepSeek 原生思维链，若启用）
                    if (output.getMetadata() != null) {
                        String reasoning = (String) output.getMetadata().get("reasoning_content");
                        if (reasoning != null && !reasoning.isEmpty()) {
                            events.add(SseEvent.thought("THINKING", reasoning));
                            // 持久化原生思维链
                            persistedReasoningSteps.add(Map.of(
                                "phase", "THINKING",
                                "label", "思考中",
                                "icon", "🤔",
                                "content", reasoning,
                                "done", true
                            ));
                        }
                    }

                    String text = output.getText();
                    if (text != null && !text.isEmpty()) {
                        replyBuffer.append(text);
                        events.add(SseEvent.chunk(text));
                        // ★ 诊断日志：追踪 LLM 文字输出
                        log.info("[SMART-DIAG] LLM text chunk (len={}): \"{}\"",
                            text.length(),
                            text.length() > 80 ? text.substring(0, 80) + "..." : text);
                    }
                }

                // ★ 诊断日志：追踪 flatMap 产生的所有事件
                for (SseEvent ev : events) {
                    if (ev.rawText()) {
                        log.info("[SMART-DIAG] flatMap event: chunk (rawText)");
                    } else {
                        String preview = "";
                        try {
                            Object d = ev.data();
                            if (d instanceof Map<?, ?> m) {
                                preview = m.toString();
                            } else {
                                preview = String.valueOf(d);
                            }
                            if (preview.length() > 100) preview = preview.substring(0, 100) + "...";
                        } catch (Exception ignored) {}
                        log.info("[SMART-DIAG] flatMap event: name={}, data={}", ev.name(), preview);
                    }
                }

                return reactor.core.publisher.Flux.fromIterable(events);
            })
            .doOnNext(event -> {
                emitter.send(event);
                // 按事件实际顺序收集持久化 blocks
                if (event.rawText()) {
                    // chunk 文本：追加到当前 text block（或创建新的）
                    String text = (String) event.data();
                    synchronized (persistedBlocks) {
                        if (!persistedBlocks.isEmpty()
                            && "text".equals(persistedBlocks.get(persistedBlocks.size() - 1).get("type"))) {
                            Map<String, Object> lastBlock = persistedBlocks.get(persistedBlocks.size() - 1);
                            lastBlock.put("content", (String) lastBlock.get("content") + text);
                        } else {
                            Map<String, Object> block = new LinkedHashMap<>();
                            block.put("type", "text");
                            block.put("content", text);
                            persistedBlocks.add(block);
                            // ★ 诊断日志：新建 text block
                            log.info("[SMART-DIAG] >>> NEW text block created (block #{}, preview: \"{}\")",
                                persistedBlocks.size(),
                                text.length() > 60 ? text.substring(0, 60) + "..." : text);
                        }
                    }
                } else if ("agent.visual".equals(event.name())) {
                    // visual 事件：直接添加 visual block（新 text block 会在下次 chunk 时创建）
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsed = (Map<String, Object>) event.data();
                        Map<String, Object> block = new LinkedHashMap<>();
                        block.put("type", "visual");
                        block.put("renderType", parsed.get("renderType"));
                        block.put("code", parsed.get("code"));
                        block.put("description", parsed.getOrDefault("description", ""));
                        synchronized (persistedBlocks) {
                            persistedBlocks.add(block);
                            // ★ 诊断日志：新建 visual block
                            log.info("[SMART-DIAG] >>> NEW visual block created (block #{}, renderType={})",
                                persistedBlocks.size(), parsed.get("renderType"));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to persist visual event: {}", e.getMessage());
                    }
                }
            })
            .doFinally(signal -> {
                // 6. 流结束：基于 ctxRef.get() 更新上下文 + 检查收敛
                try {
                    SmartContext latestCtx = ctxRef.get();

                    // ★ 兜底回填：LLM 遗忘调 update_concept_status 时推断状态
                    if (!observer.getToolsUsed().contains("update_concept_status")
                        && latestCtx.totalTurns() >= 2) {
                        latestCtx = safetyGuard.fallbackInferConceptStatus(
                            latestCtx, userMessage, replyBuffer.toString());
                        ctxRef.set(latestCtx);
                    }

                    // ★ profileSummary 同步：若调用了 write_profile 则刷新
                    if (observer.getToolsUsed().contains("write_profile")) {
                        String freshProfile = buildProfileSummary(
                            latestCtx.userId(), latestCtx.courseId());
                        latestCtx = latestCtx.withProfileSummary(freshProfile);
                        ctxRef.set(latestCtx);
                    }

                    SmartContext updatedCtx = updateContextAfterTurn(
                        latestCtx, userMessage, replyBuffer.toString(),
                        observer.getToolsUsed());
                    ctxRef.set(updatedCtx);
                    stateStore.save(updatedCtx.sessionId(), updatedCtx);

                    emitter.stateUpdate(updatedCtx);

                    // 评估概念掌握度
                    String masterySummary = buildMasterySummary(updatedCtx);
                    emitter.thought("REFLECT", masterySummary);

                    // 本轮决策总结
                    List<String> toolsUsed = observer.getToolsUsed();
                    String decisionSummary = toolsUsed.isEmpty()
                        ? "本轮直接回复，未调用工具"
                        : "本轮调用工具：" + String.join("、", toolsUsed);
                    emitter.thought("DECISION", decisionSummary);

                    if (updatedCtx.goal() != null && updatedCtx.goal().isHardConverged(updatedCtx)
                        || updatedCtx.converged()) {
                        emitter.smartConverged(updatedCtx.convergenceSummary());
                    }

                    emitter.done(updatedCtx);

                    // ★ 保存 AI 回复（含思考链和内容块持久化）
                    if (replyBuffer.length() > 0 || !persistedBlocks.isEmpty()) {
                        // 合并原生思维链 + 工具调用思考步骤 + 本轮反思/决策
                        List<Map<String, Object>> allThinkingSteps = new ArrayList<>();
                        allThinkingSteps.addAll(persistedReasoningSteps);
                        allThinkingSteps.addAll(observer.getPersistedThinkingSteps());
                        // 持久化 REFLECT 步骤
                        Map<String, Object> reflectStep = new LinkedHashMap<>();
                        reflectStep.put("phase", "REFLECT");
                        reflectStep.put("label", "评估画像");
                        reflectStep.put("icon", "🪞");
                        reflectStep.put("content", masterySummary);
                        reflectStep.put("done", true);
                        allThinkingSteps.add(reflectStep);
                        // 持久化 DECISION 步骤
                        Map<String, Object> decisionStep = new LinkedHashMap<>();
                        decisionStep.put("phase", "DECISION");
                        decisionStep.put("label", "决策判断");
                        decisionStep.put("icon", "⚖️");
                        decisionStep.put("content", decisionSummary);
                        decisionStep.put("done", true);
                        allThinkingSteps.add(decisionStep);

                        // ★ 诊断日志：最终持久化 blocks 摘要
                        log.info("[SMART-DIAG] ===== FINAL BLOCKS SUMMARY =====");
                        log.info("[SMART-DIAG] replyBuffer.length={}, blocks.size={}, thinkingSteps.size={}",
                            replyBuffer.length(), persistedBlocks.size(), allThinkingSteps.size());
                        for (int i = 0; i < persistedBlocks.size(); i++) {
                            Map<String, Object> blk = persistedBlocks.get(i);
                            String tp = (String) blk.get("type");
                            if ("text".equals(tp)) {
                                String c = (String) blk.get("content");
                                log.info("[SMART-DIAG] block[{}]: type=text, len={}, preview=\"{}\"",
                                    i, c.length(),
                                    c.length() > 80 ? c.substring(0, 80) + "..." : c);
                            } else {
                                log.info("[SMART-DIAG] block[{}]: type={}, renderType={}", i, tp, blk.get("renderType"));
                            }
                        }
                        log.info("[SMART-DIAG] ===== END BLOCKS SUMMARY =====");

                        saveAssistantMessage(chatId, updatedCtx.userId(),
                            replyBuffer.toString(), observer.getToolsUsed(),
                            allThinkingSteps,
                            persistedBlocks);
                    }

                    log.info("SmartAgentLoop: turn completed, totalTurns={}, tools={}",
                        updatedCtx.totalTurns(), observer.getToolsUsed());

                } catch (Exception e) {
                    log.error("SmartAgentLoop doFinally error: {}", e.getMessage(), e);
                    emitter.error("SMART_FINALIZE_ERROR", e.getMessage(), false);
                }
            })
            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
            .blockLast();
    }

    // ================================================================
    // 系统提示词
    // ================================================================

    private String buildSystemPrompt(SmartContext ctx, List<String> toolNames) {
        return """
            你是学思伴行的智能辅导老师。你通过自然对话帮助学生理解和掌握知识。

            ## 你的教学方式

            你像真人老师一样辅导学生：
            1. **理解学生需求**--分析学生的问题、困惑、水平
            2. **自主选择方法**--决定用什么方式帮助学生：讲解、画图、出题、举例、查资料...
            3. **使用工具**--你可以调用多种工具来辅助教学
            4. **观察调整**--根据学生的反应调整教学策略
            5. **自然对话**--始终保持自然、友好的对话风格，不机械

            ## 当前教学上下文

            %s

            ## 可用工具

            %s

            ## 教学原则

            1. **以辅导为核心**--无论学生说什么（反问/跑题/情绪），都以帮助学生理解为目标回应
            2. **产物自然融合**--文字和工具产出在对话中自然交替，不要突兀地丢一个图出来
            3. **先理解再决定**--先理解学生状态，再决定用什么工具，不要上来就画图
            4. **适时收敛**--所有概念掌握后，做一次迁移检验，然后总结收尾
            5. **鼓励探索**--鼓励学生提问、尝试、动手
            6. **简短有力**--每次回复控制在合理篇幅，不要长篇大论

            ## 收敛规则

            - 每个概念必须通过 assess_concept 探测并确认掌握（update_concept_status -> MASTERED）
            - 所有概念 MASTERED 后，调用 challenge_transfer 做一次迁移检验
            - 迁移通过后，调用 generate_summary 生成总结
            - 收敛后不再出题，让学生自由提问或结束

            ## 输出要求

            - 直接输出对话内容，自然友好
            - 需要可视化时调用工具，工具结果会自动展示给学生
            - 不要解释你在做什么（"我现在要画一个图"），直接画
            - 用中文交流
            """.formatted(ctx.toContextBlock(), buildToolDescriptions(toolNames));
    }

    /** 工具描述映射表 */
    private static final Map<String, String> TOOL_DESCRIPTIONS = Map.ofEntries(
        Map.entry("generate_svg",
            "- **generate_svg**：生成 SVG 矢量图。用于示意图、结构图、空间关系、直觉图解。当你判断\"画个图学生更容易理解\"时调用。"),
        Map.entry("generate_chart",
            "- **generate_chart**：生成 Chart.js 图表。用于定量数据、对比、趋势。当涉及数值关系时调用。"),
        Map.entry("generate_mermaid",
            "- **generate_mermaid**：生成 Mermaid 图。用于流程图、序列图、类图、状态图。当涉及步骤、结构、关系时调用。"),
        Map.entry("generate_mindmap",
            "- **generate_mindmap**：生成思维导图（JSON 树形结构）。用于知识体系梳理、概念关系图、脑图。当需要梳理知识结构或概念层级时调用。"),
        Map.entry("generate_html",
            "- **generate_html**：生成交互式 HTML 页面。用于需要学生操作的演示。当需要\"动手试试\"才能理解时调用。"),
        Map.entry("generate_visualization",
            "- **generate_visualization**：生成2D动态可视化（算法演示/数据结构/数学/物理过程）。使用状态机+Canvas 2D，自动播放+循环+叙事讲解。当需要展示动态过程或算法执行步骤时调用。"),
        Map.entry("generate_threejs",
            "- **generate_threejs**：生成 Three.js 3D 交互场景。用于立体模型、空间结构、几何演示、分子结构、建筑模型。当需要学生\"转着看\"才能理解的 3D 概念时调用。"),
        Map.entry("generate_image",
            "- **generate_image**：AI 生成图片。用于装饰性插图、概念可视化。"),
        Map.entry("rag_retrieve",
            "- **rag_retrieve**：检索课程知识库。回答知识性问题时主动调用。"),
        Map.entry("web_search",
            "- **web_search**：网络搜索。扩展知识时调用。"),
        Map.entry("paper_search",
            "- **paper_search**：论文搜索。"),
        Map.entry("assess_concept",
            "- **assess_concept**：出题探测概念掌握度。**必须用此工具出题**（不用直接文字出题），这样系统才能追踪掌握状态。用判断/选择题快速判断学生是否掌握。"),
        Map.entry("challenge_transfer",
            "- **challenge_transfer**：出变形题检验迁移能力。当所有概念已掌握时调用。"),
        Map.entry("update_concept_status",
            "- **update_concept_status**：更新概念掌握状态。评估学生回答后调用。"),
        Map.entry("generate_summary",
            "- **generate_summary**：生成阶段总结。收敛时调用。"),
        Map.entry("reason",
            "- **reason**：深度推理。复杂问题需要独立推理时调用。"),
        Map.entry("brainstorm",
            "- **brainstorm**：头脑风暴。需要多角度探索时调用。"),
        Map.entry("code_execution",
            "- **code_execution**：沙箱执行代码。学生需要动手实践时调用。"),
        Map.entry("read_profile",
            "- **read_profile**：读取学生画像。"),
        Map.entry("write_profile",
            "- **write_profile**：更新画像。"),
        Map.entry("ask_user",
            "- **ask_user**：向学生提问（非探测性的开放问题，如\"你想从哪个角度理解？\"）。★ 注意：探测概念掌握度时用 assess_concept，不用 ask_user。")
    );

    /** 根据 toolNames 动态生成工具描述列表 */
    private String buildToolDescriptions(List<String> toolNames) {
        StringBuilder sb = new StringBuilder();
        for (String name : toolNames) {
            String desc = TOOL_DESCRIPTIONS.get(name);
            if (desc != null) {
                sb.append(desc).append("\n");
            } else {
                sb.append("- **").append(name).append("**：（工具描述未知）\n");
            }
        }
        return sb.toString();
    }

    // ================================================================
    // 消息构建
    // ================================================================

    private List<Message> buildMessages(String systemPrompt, SmartContext ctx,
                                         String chatId, String userMessage) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));

        // 从 chat_messages 加载历史
        List<ChatMessage> history = chatMessageMapper.selectBySessionId(chatId);
        int maxMessages = config.getSmart().getContextWindowMessages();
        int maxToolResultChars = config.getSmart().getMaxToolResultChars();

        if (history != null && !history.isEmpty()) {
            // 更早的历史用 TurnSummary 压缩
            if (ctx.turns() != null && ctx.turns().size() > config.getSmart().getContextWindowRounds()) {
                String summaryBlock = buildEarlyTurnSummary(ctx,
                    config.getSmart().getContextWindowRounds());
                messages.add(new SystemMessage("## 早期对话摘要\n" + summaryBlock));
            }

            // 转换历史消息（只取最近 N 条，工具结果超阈值则截断）
            int startIdx = Math.max(0, history.size() - maxMessages);
            for (int i = startIdx; i < history.size(); i++) {
                ChatMessage msg = history.get(i);
                String content = msg.getContent();
                if (content != null && content.length() > maxToolResultChars) {
                    content = content.substring(0, maxToolResultChars) + "\n...[结果已截断]";
                }
                if ("user".equals(msg.getRole())) {
                    messages.add(new UserMessage(content));
                } else if ("assistant".equals(msg.getRole())) {
                    messages.add(new AssistantMessage(content));
                }
            }
        }

        // 当前用户消息（若未已在历史中）
        if (userMessage != null && !userMessage.isBlank()) {
            // 检查最后一条是否已经是当前用户消息（避免重复）
            boolean alreadyIncluded = false;
            if (messages.size() > 1) {
                Message last = messages.get(messages.size() - 1);
                if (last instanceof UserMessage um && um.getText().equals(userMessage)) {
                    alreadyIncluded = true;
                }
            }
            if (!alreadyIncluded) {
                messages.add(new UserMessage(userMessage));
            }
        }

        return messages;
    }

    private String buildEarlyTurnSummary(SmartContext ctx, int keepRounds) {
        StringBuilder sb = new StringBuilder();
        int start = 0;
        int end = Math.max(0, ctx.turns().size() - keepRounds);
        for (int i = start; i < end; i++) {
            sb.append(ctx.turns().get(i).toLine()).append("\n");
        }
        return sb.toString();
    }

    // ================================================================
    // 工具解析
    // ================================================================

    /**
     * 解析工具名为 ToolCallback 列表。
     * 三类来源：
     * 1. ToolRegistry 中的 AgentTool -> 用 ToolCallbackFactory.wrap() 包装
     * 2. per-session 教学工具 -> 按次构造，注入 ctxRef（不进 ToolRegistry）
     * 3. 可视化工具 -> 按次构造
     */
    private List<ToolCallback> resolveToolCallbacks(List<String> toolNames,
            AtomicReference<SmartContext> ctxRef, SmartContext ctx, SmartEventEmitter emitter) {
        List<ToolCallback> callbacks = new ArrayList<>();
        Map<String, Object> injectedArgs = new HashMap<>();
        injectedArgs.put("course_id", ctx.courseId());
        injectedArgs.put("user_id", ctx.userId());

        for (String name : toolNames) {
            // 1. per-session 教学工具（需要 ctxRef，按次构造）
            switch (name) {
                case "assess_concept" -> {
                    callbacks.add(callbackFactory.wrap(
                        new AssessConceptTool(chatClientBuilder.build(), ctx.concepts()),
                        injectedArgs));
                    continue;
                }
                case "challenge_transfer" -> {
                    callbacks.add(callbackFactory.wrap(
                        new ChallengeTransferTool(chatClientBuilder.build(), ctx.concepts()),
                        injectedArgs));
                    continue;
                }
                case "update_concept_status" -> {
                    callbacks.add(callbackFactory.wrap(
                        new UpdateConceptStatusTool(ctxRef),
                        injectedArgs));
                    continue;
                }
                case "generate_summary" -> {
                    callbacks.add(callbackFactory.wrap(
                        new GenerateSummaryTool(chatClientBuilder.build(), ctxRef),
                        injectedArgs));
                    continue;
                }
            }

            // 2. 可视化工具（按次构造，需要 chatClientBuilder）
            switch (name) {
                case "generate_svg" -> {
                    if (smartSvgTool != null) {
                        callbacks.add(callbackFactory.wrap(smartSvgTool, injectedArgs));
                    }
                    continue;
                }
                case "generate_chart" -> {
                    if (generateChartTool != null) {
                        callbacks.add(callbackFactory.wrap(generateChartTool, injectedArgs));
                    }
                    continue;
                }
                case "generate_mermaid" -> {
                    if (generateMermaidTool != null) {
                        callbacks.add(callbackFactory.wrap(generateMermaidTool, injectedArgs));
                    }
                    continue;
                }
                case "generate_mindmap" -> {
                    if (generateMindmapTool != null) {
                        callbacks.add(callbackFactory.wrap(generateMindmapTool, injectedArgs));
                    }
                    continue;
                }
                case "generate_html" -> {
                    if (generateHtmlTool != null) {
                        callbacks.add(callbackFactory.wrap(generateHtmlTool, injectedArgs));
                    }
                    continue;
                }
                case "generate_visualization" -> {
                    if (generateVisualizationTool != null) {
                        callbacks.add(callbackFactory.wrap(generateVisualizationTool, injectedArgs));
                    }
                    continue;
                }
                case "generate_threejs" -> {
                    if (generateThreeJsTool != null) {
                        callbacks.add(callbackFactory.wrap(generateThreeJsTool, injectedArgs));
                    }
                    continue;
                }
            }

            // 3. ToolRegistry 中的共享 AgentTool
            AgentTool agentTool = toolRegistry.get(name);
            if (agentTool != null) {
                callbacks.add(callbackFactory.wrap(agentTool, injectedArgs));
            } else {
                log.warn("Unknown tool not resolved: {}", name);
            }
        }

        return callbacks;
    }

    // ================================================================
    // 概念拆解
    // ================================================================

    /**
     * 使用 LLM 生成概念拆解。
     */
    private List<ConceptBreakdown> generateConceptBreakdown(String question, String courseId) {
        try {
            String systemPrompt = """
                你是一个教学分析专家。请分析学生的问题，拆解出需要掌握的子概念。
                返回 JSON 数组，每个元素包含：
                - id: concept_1, concept_2, ...
                - label: 概念名称
                - description: 概念描述
                - expectedAnswer: 预期学生应掌握的回答
                - difficulty: easy | medium | hard
                - dependsOn: 依赖的其他概念ID列表

                只返回 JSON 数组，不要其他内容。
                通常拆解 2-4 个子概念。
                """;

            String userRequest = "学生问题：" + question;

            String response = chatClientBuilder.build().prompt()
                .system(systemPrompt)
                .user(userRequest)
                .call()
                .content();

            return parseConceptBreakdown(response);

        } catch (Exception e) {
            log.error("generateConceptBreakdown failed: {}", e.getMessage(), e);
            // 降级：返回单个概念
            return List.of(new ConceptBreakdown(
                "concept_1", question, "", "", "medium", List.of()
            ));
        }
    }

    @SuppressWarnings("unchecked")
    private List<ConceptBreakdown> parseConceptBreakdown(String json) {
        if (json == null || json.isBlank()) return List.of();

        try {
            // 尝试提取 JSON 数组
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start >= 0 && end > start) {
                String jsonArray = json.substring(start, end + 1);
                List<Map<String, Object>> items = MAPPER.readValue(jsonArray, new TypeReference<>() {});
                List<ConceptBreakdown> result = new ArrayList<>();
                for (Map<String, Object> item : items) {
                    result.add(new ConceptBreakdown(
                        String.valueOf(item.getOrDefault("id", "concept_" + (result.size() + 1))),
                        String.valueOf(item.getOrDefault("label", "")),
                        item.get("description") != null ? String.valueOf(item.get("description")) : null,
                        item.get("expectedAnswer") != null ? String.valueOf(item.get("expectedAnswer")) : null,
                        String.valueOf(item.getOrDefault("difficulty", "medium")),
                        item.get("dependsOn") != null ? (List<String>) item.get("dependsOn") : List.of()
                    ));
                }
                return result.isEmpty() ? List.of() : result;
            }
        } catch (Exception e) {
            log.warn("parseConceptBreakdown failed: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * 简单的问题分析（用于 smart.started 事件）。
     */
    private QuestionAnalysis analyzeQuestion(String question) {
        try {
            String response = chatClientBuilder.build().prompt()
                .system("""
                    分析学生的问题，返回 JSON：
                    {"questionType":"...","domain":"...","subDomain":"...","difficulty":"easy|medium|hard",
                     "keyConcepts":["..."],"prerequisiteGaps":["..."],"realIntent":"..."}
                    只返回 JSON，不要其他内容。
                    """)
                .user("问题：" + question)
                .call()
                .content();

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = MAPPER.readValue(response.trim(), Map.class);
            return new QuestionAnalysis(
                String.valueOf(parsed.getOrDefault("questionType", "unknown")),
                String.valueOf(parsed.getOrDefault("domain", "unknown")),
                String.valueOf(parsed.getOrDefault("subDomain", "")),
                String.valueOf(parsed.getOrDefault("difficulty", "medium")),
                parsed.get("keyConcepts") != null ? (List<String>) parsed.get("keyConcepts") : List.of(),
                parsed.get("prerequisiteGaps") != null ? (List<String>) parsed.get("prerequisiteGaps") : List.of(),
                String.valueOf(parsed.getOrDefault("realIntent", question))
            );
        } catch (Exception e) {
            log.warn("analyzeQuestion failed: {}", e.getMessage());
            return new QuestionAnalysis("unknown", "unknown", "", "medium",
                List.of(), List.of(), question);
        }
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    /**
     * 构建画像摘要。
     */
    private String buildProfileSummary(String userId, String courseId) {
        try {
            Map<String, Object> profile = profileService.getProfile(userId, courseId);
            if (profile == null || profile.isEmpty()) {
                return "（暂无画像信息）";
            }
            // 简单格式化画像摘要
            StringBuilder sb = new StringBuilder();
            Object displayJson = profile.get("display_json");
            if (displayJson != null) {
                sb.append(displayJson);
            } else {
                // 遍历 key-value
                profile.forEach((k, v) -> {
                    if (v != null && !"null".equals(String.valueOf(v))) {
                        sb.append(k).append(": ").append(v).append("\n");
                    }
                });
            }
            return sb.length() > 0 ? sb.toString() : "（暂无画像信息）";
        } catch (Exception e) {
            log.debug("buildProfileSummary failed: {}", e.getMessage());
            return "（暂无画像信息）";
        }
    }

    /**
     * 每轮对话后更新上下文。
     * <p>用更新后的 turns 和 totalTurns 检查收敛，
     * 这样当前轮调用的 challenge_transfer 等工具能被 isHardConverged 检测到。
     * 同时保留工具（如 generate_summary）可能已经通过 ctxRef 设置的 converged 和 convergenceSummary。</p>
     */
    private SmartContext updateContextAfterTurn(SmartContext ctx, String userMessage,
                                                 String reply, List<String> toolsUsed) {
        TurnSummary turn = new TurnSummary(
            ctx.totalTurns() + 1,
            summarizeTurn(userMessage, reply),
            String.join(", ", toolsUsed),
            extractConceptUpdate(toolsUsed)
        );

        List<TurnSummary> newTurns = new ArrayList<>(ctx.turns() != null ? ctx.turns() : List.of());
        newTurns.add(turn);
        int newTotalTurns = ctx.totalTurns() + 1;

        // 先构建更新后的上下文（保留工具可能已设置的 converged/convergenceSummary）
        SmartContext updated = new SmartContext(
            ctx.sessionId(), ctx.userId(), ctx.courseId(),
            ctx.concepts(), ctx.conceptStatus(),
            ctx.profileSummary(), ctx.goal(),
            newTurns, newTotalTurns,
            ctx.converged(), ctx.convergenceSummary());

        // 用更新后的上下文检查收敛（包含当前轮的 turn 和 totalTurns）
        boolean converged = updated.converged()
            || (updated.goal() != null && updated.goal().isHardConverged(updated));

        return new SmartContext(
            updated.sessionId(), updated.userId(), updated.courseId(),
            updated.concepts(), updated.conceptStatus(),
            updated.profileSummary(), updated.goal(),
            updated.turns(), updated.totalTurns(),
            converged, updated.convergenceSummary());
    }

    private String summarizeTurn(String userMessage, String reply) {
        String userSnippet = userMessage != null && userMessage.length() > 50
            ? userMessage.substring(0, 50) + "..." : userMessage;
        String replySnippet = reply != null && reply.length() > 50
            ? reply.substring(0, 50) + "..." : reply;
        return "学生: " + userSnippet + " | 回复: " + replySnippet;
    }

    private String extractConceptUpdate(List<String> toolsUsed) {
        if (toolsUsed.contains("update_concept_status")) {
            return "概念状态已更新";
        }
        return "";
    }

    /** 构建概念掌握度摘要（用于 REFLECT 阶段） */
    private String buildMasterySummary(SmartContext ctx) {
        if (ctx.concepts() == null || ctx.concepts().isEmpty()) {
            return "暂无概念数据";
        }
        int mastered = 0, unclear = 0, unverified = 0;
        for (ConceptBreakdown c : ctx.concepts()) {
            ConceptStatus s = ctx.conceptStatus() == null
                ? ConceptStatus.UNVERIFIED
                : ctx.conceptStatus().getOrDefault(c.id(), ConceptStatus.UNVERIFIED);
            switch (s) {
                case MASTERED -> mastered++;
                case UNCLEAR -> unclear++;
                default -> unverified++;
            }
        }
        int total = ctx.concepts().size();
        return "概念掌握度：" + mastered + "/" + total + " 已掌握"
            + (unclear > 0 ? "，" + unclear + " 不清晰" : "")
            + (unverified > 0 ? "，" + unverified + " 未检测" : "")
            + "（第 " + ctx.totalTurns() + " 轮）";
    }

    // ================================================================
    // 消息持久化
    // ================================================================

    /** 懒创建 chat session（与 TutoringServiceImpl 行为一致） */
    private void ensureChatSession(String chatId, String userId, String courseId, String question) {
        try {
            if (chatId != null && chatSessionMapper.selectById(chatId) == null) {
                ChatSession chatSession = new ChatSession();
                chatSession.setId(chatId);
                chatSession.setUserId(userId);
                chatSession.setCourseId(courseId != null ? courseId : "");
                chatSession.setType("chat");
                chatSession.setStatus("active");
                chatSession.setMessageCount(0);
                chatSession.setCurrentRound(0);
                chatSession.setTitle(question != null && question.length() > 0
                    ? (question.length() > 30 ? question.substring(0, 30) + "…" : question)
                    : "智能辅导会话");
                chatSession.setCreatedAt(LocalDateTime.now());
                chatSession.setUpdatedAt(LocalDateTime.now());
                chatSessionMapper.insert(chatSession);
                log.info("Lazy-created chat session for smart: chatId={}, userId={}", chatId, userId);
            }
        } catch (Exception e) {
            log.warn("ensureChatSession failed: {}", e.getMessage());
        }
    }

    private void saveUserMessage(String chatId, String userId, String content) {
        try {
            ChatMessageService.MessageSeq userSeq = chatMessageService.allocateSeq(chatId, "user");
            ChatMessage userMsg = new ChatMessage();
            userMsg.setSessionId(chatId);
            userMsg.setUserId(userId);
            userMsg.setSeqNum(userSeq.seqNum());
            userMsg.setRoundNum(userSeq.roundNum());
            userMsg.setRole("user");
            userMsg.setContent(content);
            userMsg.setMode("smart");
            chatMessageMapper.insert(userMsg);
        } catch (Exception e) {
            log.warn("saveUserMessage failed: {}", e.getMessage());
        }
    }

    private void saveAssistantMessage(String chatId, String userId, String reply,
                                       List<String> toolsUsed) {
        saveAssistantMessage(chatId, userId, reply, toolsUsed,
            List.of(), List.of());
    }

    /**
     * 保存 AI 回复消息，含思考链和内容块（文字与可视化交替顺序）。
     *
     * @param blocks 按事件实际顺序收集的内容块，每个 block 是 {type:text|visual, ...}
     */
    private void saveAssistantMessage(String chatId, String userId, String reply,
                                       List<String> toolsUsed,
                                       List<Map<String, Object>> thinkingSteps,
                                       List<Map<String, Object>> blocks) {
        try {
            ChatMessageService.MessageSeq aiSeq = chatMessageService.allocateSeq(chatId, "assistant");
            ChatMessage aiMsg = new ChatMessage();
            aiMsg.setSessionId(chatId);
            aiMsg.setUserId(userId);
            aiMsg.setSeqNum(aiSeq.seqNum());
            aiMsg.setRoundNum(aiSeq.roundNum());
            aiMsg.setRole("assistant");
            aiMsg.setContent(reply);
            aiMsg.setMode("smart");

            // 持久化思考链和内容块到 metadataJson
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (!toolsUsed.isEmpty()) {
                metadata.put("tools_used", toolsUsed);
            }
            if (!thinkingSteps.isEmpty()) {
                metadata.put("thinking_steps", thinkingSteps);
            }
            // blocks 已按事件实际顺序收集（文字和可视化交替），直接持久化
            if (!blocks.isEmpty()) {
                metadata.put("blocks", blocks);
            }
            if (!metadata.isEmpty()) {
                aiMsg.setMetadataJson(MAPPER.writeValueAsString(metadata));
            }
            chatMessageMapper.insert(aiMsg);
        } catch (Exception e) {
            log.warn("saveAssistantMessage failed: {}", e.getMessage());
        }
    }
}
