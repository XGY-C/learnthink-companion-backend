package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.chat.*;
import com.learnthink.common.dto.chat.ThinkingPhase;
import com.learnthink.core.agent.runtime.AgentContext;
import com.learnthink.core.agent.runtime.AgentObservation;
import com.learnthink.core.agent.runtime.AgentResult;
import com.learnthink.core.agent.PlannerOutput;
import com.learnthink.core.agent.PlannerOutput.ResourceRequirements;
import com.learnthink.core.agent.impl.BookInfoTool;
import com.learnthink.core.agent.impl.BookInfoToolCallback;
import com.learnthink.core.agent.impl.ConversationAgent;
import com.learnthink.core.agent.impl.RagTool;
import com.learnthink.core.agent.impl.ConversationPlanner;
import com.learnthink.core.agent.impl.RagToolCallback;
import com.learnthink.core.agent.impl.ReplyGenerator;
import com.learnthink.core.agent.impl.UnifiedGenerator;
import com.learnthink.core.config.LearnThinkProperties;
import com.learnthink.core.config.PromptLoader;
import com.learnthink.core.domain.entity.AgentThinkingTrace;
import com.learnthink.core.domain.entity.BookInfo;
import com.learnthink.core.domain.entity.Profile;
import com.learnthink.core.domain.entity.ProfileChat;
import com.learnthink.core.domain.entity.ProfileVersion;
import com.learnthink.core.domain.entity.ResourceItem;
import com.learnthink.core.domain.entity.ResourcePack;
import com.learnthink.core.domain.entity.Task;
import com.learnthink.core.repository.CourseMapper;
import com.learnthink.core.repository.ProfileChatMapper;
import com.learnthink.core.repository.ProfileMapper;
import com.learnthink.core.domain.entity.LearningPlan;
import com.learnthink.core.repository.LearningPlanMapper;
import com.learnthink.core.repository.ProfileVersionMapper;
import com.learnthink.core.repository.ResourceItemMapper;
import com.learnthink.core.repository.ResourcePackMapper;
import com.learnthink.core.repository.TaskMapper;
import com.learnthink.core.service.ChatService;
import com.learnthink.core.service.KpAnchorService;
import com.learnthink.common.dto.profile.ProfileMdSet;
import com.learnthink.core.domain.entity.ProfileSignal;
import com.learnthink.core.service.ProfileService;
import com.learnthink.core.service.TaskPersistenceService;
import com.learnthink.core.domain.entity.TutoringSession;
import com.learnthink.core.service.chat.ChatSessionService;
import com.learnthink.core.service.profile.ProfileSignalService;
import com.learnthink.core.tutoring.repository.TutoringSessionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 聊天服务实现类
 * <p>
 * 核心职责：
 * 1. 管理用户画像对话会话（ProfileChat）的生命周期。
 * 2. 编排多智能体协作流：意图探测 -> RAG检索 -> LLM回复 -> 画像充足度评估。
 * 3. 处理 SSE 流式响应，实时推送 Agent 思考过程与内容片段。
 * 4. 触发异步画像分析（Delta更新）与 KP 锚定任务。
 */
@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    private final ProfileChatMapper profileChatMapper;
    private final ProfileMapper profileMapper;
    private final ProfileVersionMapper profileVersionMapper;
    private final CourseMapper courseMapper;
    private final TaskMapper taskMapper;
    private final LearningPlanMapper learningPlanMapper;
    private final ResourcePackMapper resourcePackMapper;
    private final ResourceItemMapper resourceItemMapper;
    private final ChatSessionService sessionService;
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final PromptLoader promptLoader;
    private final TaskPersistenceService persistenceService;
    private final ProfileService profileService;
    private final ConversationAgent conversationAgent;
    private final RagTool ragTool;
    private final BookInfoTool bookInfoTool;
    private final ConversationPlanner conversationPlanner;
    private final ReplyGenerator replyGenerator;
    private final UnifiedGenerator unifiedGenerator;
    private final LearnThinkProperties learnThinkProperties;
    private final KpAnchorService kpAnchorService;
    private final ProfileSignalService signalService;
    private final TutoringSessionMapper tutoringSessionMapper;
    private final StringRedisTemplate redis;
    private final ExecutorService profileAnalysisExecutor = Executors.newFixedThreadPool(2);

    public ChatServiceImpl(ProfileChatMapper profileChatMapper,
                           ProfileMapper profileMapper,
                           ProfileVersionMapper profileVersionMapper,
                           CourseMapper courseMapper,
                           TaskMapper taskMapper,
                           LearningPlanMapper learningPlanMapper,
                           ResourcePackMapper resourcePackMapper,
                           ResourceItemMapper resourceItemMapper,
                           @Qualifier("chatChatClientBuilder") ChatClient.Builder chatClientBuilder,
                           ObjectMapper objectMapper,
                           PromptLoader promptLoader,
                           TaskPersistenceService persistenceService,
                           ProfileService profileService,
                           ConversationAgent conversationAgent,
                           RagTool ragTool,
                           BookInfoTool bookInfoTool,
                           KpAnchorService kpAnchorService,
                           ProfileSignalService signalService,
                           ConversationPlanner conversationPlanner,
                           ReplyGenerator replyGenerator,
                           UnifiedGenerator unifiedGenerator,
                           LearnThinkProperties learnThinkProperties,
                ChatSessionService sessionService,
                TutoringSessionMapper tutoringSessionMapper,
                StringRedisTemplate redis) {
        this.profileChatMapper = profileChatMapper;
        this.profileMapper = profileMapper;
        this.profileVersionMapper = profileVersionMapper;
        this.courseMapper = courseMapper;
        this.taskMapper = taskMapper;
        this.learningPlanMapper = learningPlanMapper;
        this.resourcePackMapper = resourcePackMapper;
        this.resourceItemMapper = resourceItemMapper;
        this.chatClientBuilder = chatClientBuilder;
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
        this.persistenceService = persistenceService;
        this.profileService = profileService;
        this.conversationAgent = conversationAgent;
        this.ragTool = ragTool;
        this.bookInfoTool = bookInfoTool;
        this.kpAnchorService = kpAnchorService;
        this.signalService = signalService;
        this.conversationPlanner = conversationPlanner;
        this.replyGenerator = replyGenerator;
        this.unifiedGenerator = unifiedGenerator;
        this.learnThinkProperties = learnThinkProperties;
        this.sessionService = sessionService;
        this.tutoringSessionMapper = tutoringSessionMapper;
        this.redis = redis;
    }

    /**
     * 启动或恢复聊天会话
     * <p>
     * 策略：优先复用已存在的活跃会话；若开启强制新建，则生成 UUID 并采用“懒创建”模式（首次发消息时落库）。
     *
     * @param userId  当前用户ID
     * @param request 包含课程ID及是否强制新建的请求对象
     * @return 会话初始化响应，包含 chatId 及历史消息
     */
    @Override
    @Transactional
    public ChatStartResponse startChat(String userId, ChatStartRequest request) {
        if (!request.isForceNew()) {
            ProfileChat existing = findActiveSessionWithMessages(userId, request.getCourseId());
            if (existing == null) {
                existing = findLatestNonEmptySession(userId, request.getCourseId());
            }
            if (existing != null) {
                List<ChatMessageDto> messages = parseMessages(existing.getMessagesJson());
                return buildStartResponse(existing.getId(), request.getCourseId(), messages);
            }
        }

        // 懒创建：仅返回 UUID，首次发消息时再落库
        String newId = java.util.UUID.randomUUID().toString();
        return buildStartResponse(newId, request.getCourseId(), List.of());
    }

    /**
     * 发送消息并获取同步回复
     * <p>
     * 业务流程：
     * 1. 校验并持久化用户消息。
     * 2. 预检生成意图（Resource/Plan模式）。
     * 3. 调用 ConversationAgent 执行单轮对话逻辑。
     * 4. 根据画像充足度或用户意图，决定是否触发资源生成的异步流程。
     *
     * @param userId  用户ID
     * @param chatId  会话ID
     * @return 包含 AI 回复、画像状态及生成就绪标识的响应
     */
    @Override
    public ChatMessagesResponse getMessages(String userId, String chatId) {
        ProfileChat chat = profileChatMapper.selectById(chatId);
        if (chat == null || !chat.getUserId().equals(userId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Chat session not found");
        }
        List<ChatMessageDto> messages = parseMessages(chat.getMessagesJson());

        // v3.5: 若历史消息的 thinking 为 null 或仅有 2 步固定步骤，
        // 尝试从 agent_thinking_traces 表中按 chat_id + round_num 重建完整思考链
        reconstructThinkingForMessages(messages, chatId);

        // 查询关联的活跃任务
        List<Task> activeTasks = taskMapper.findByChatId(chatId);
        List<ActiveTaskDto> activeTaskDtos = new ArrayList<>();
        for (Task task : activeTasks) {
            ActiveTaskDto dto = new ActiveTaskDto();
            dto.setTaskId(task.getId());
            dto.setTopic(task.getTopic());
            dto.setStatus(task.getStatus());
            dto.setStage(task.getStage());
            dto.setTaskType(task.getTaskType());
            dto.setPercent(task.getPercent() != null ? task.getPercent() : 0);
            List<String> resourceTypes = parseResourceTypes(task.getRequestedResourceTypes());
            dto.setResourceTypes(resourceTypes);
            dto.setTotalCount(resourceTypes != null ? resourceTypes.size() : 0);
            dto.setErrorMessage(task.getErrorMessage());

            // 统计已完成任务的就绪资源数
            if ("SUCCEEDED".equals(task.getStatus())) {
                try {
                    var pack = resourcePackMapper.selectOne(
                        new LambdaQueryWrapper<ResourcePack>()
                            .eq(ResourcePack::getTaskId, task.getId()));
                    if (pack != null) {
                        long readyCount = resourceItemMapper.selectCount(
                            new LambdaQueryWrapper<ResourceItem>()
                                .eq(ResourceItem::getPackId, pack.getId())
                                .eq(ResourceItem::getStatus, "ready"));
                        dto.setReadyCount((int) readyCount);
                    }
                } catch (Exception e) {
                    log.warn("统计任务 {} 的资源数失败: {}", task.getId(), e.getMessage());
                }
            }

            activeTaskDtos.add(dto);
        }

        // 从历史消息中检测是否有未被处理的 plan offer（资源/学习计划）
        boolean hasResourceOffer = false;
        boolean hasPlanOffer = false;
        int planOfferIdx = -1;
        Map<String, Object> planOfferMeta = null;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessageDto msg = messages.get(i);
            if (msg.getPlanOffer() instanceof Map<?, ?> po) {
                String type = (String) po.get("type");
                if ("resource".equals(type)) {
                    hasResourceOffer = true;
                } else if ("plan".equals(type)) {
                    hasPlanOffer = true;
                    planOfferMeta = (Map<String, Object>) po;
                    planOfferIdx = i;
                }
            }
        }

        // 检查活跃任务中是否有 plan / resource 类型任务
        // 只有 PENDING / RUNNING 状态才算"活跃"，SUCCEEDED / FAILED 不算
        boolean hasActivePlanTask = activeTaskDtos.stream()
            .anyMatch(t -> "plan_generate".equals(t.getTaskType())
                && ("PENDING".equals(t.getStatus()) || "RUNNING".equals(t.getStatus())));
        boolean hasActiveResourceTask = activeTaskDtos.stream()
            .anyMatch(t -> "resource_generate".equals(t.getTaskType())
                && ("PENDING".equals(t.getStatus()) || "RUNNING".equals(t.getStatus())));

        // v3.1: 从 DB 加载已有计划，直接返回 pendingPlan 供前端 PlanEditor 渲染
        // 避免重新调用 /plan/preview（LLM 非确定性），切换会话不丢编辑
        Map<String, Object> pendingPlan = null;
        LearningPlan existingPlan = learningPlanMapper.findByChatId(chatId);
        if (existingPlan != null) {
            String planStatus = existingPlan.getStatus();
            try {
                pendingPlan = objectMapper.readValue(existingPlan.getPlanJson(),
                        new TypeReference<Map<String, Object>>() {});
                pendingPlan.put("plan_id", existingPlan.getId());
                pendingPlan.put("status", planStatus);
            } catch (Exception e) {
                log.warn("Failed to parse plan JSON for planId={}: {}", existingPlan.getId(), e.getMessage());
            }

            // 不清除 planOffer — 前端需要找到原始消息来挂载 _pendingPlan
            // 通过 planOfferMessageIdx 响应字段精确定位，避免 fallback 到错误消息
        }

        // 资源 offer：有 offer 且没有活跃资源任务 → 显示确认卡片
        boolean generationReady = hasResourceOffer && !hasActiveResourceTask;
        Map<String, Object> generationMeta = generationReady ? Map.of("stage", "offered") : null;

        // 计划 offer：有 offer 且没有活跃 plan 任务且 DB 中无计划 → 显示 PlanEditor
        boolean planGenerationReady = hasPlanOffer && !hasActivePlanTask && pendingPlan == null;
        Map<String, Object> planGenerationMetaMap = planGenerationReady ? planOfferMeta : Map.of();

        return new ChatMessagesResponse(messages, generationReady, generationMeta,
            planGenerationReady, planGenerationMetaMap, activeTaskDtos, pendingPlan,
            planOfferIdx >= 0 ? planOfferIdx : null);
    }

    private List<String> parseResourceTypes(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public List<ChatSessionDto> getSessions(String userId, String courseId) {
        List<ChatSessionDto> result = new ArrayList<>();

        // 1. Query profile_chats (对话)
        LambdaQueryWrapper<ProfileChat> q = new LambdaQueryWrapper<>();
        q.eq(ProfileChat::getUserId, userId)
         .eq(ProfileChat::getCourseId, courseId)
         .orderByDesc(ProfileChat::getCreatedAt);

        for (ProfileChat c : profileChatMapper.selectList(q)) {
            List<Map<String, String>> messages = parseRawMessages(c.getMessagesJson());
            String title = "新会话";
            String lastMessageAt = c.getCreatedAt() != null
                ? c.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : "";
            String lastMessagePreview = "";

            for (Map<String, String> msg : messages) {
                String role = msg.get("role");
                String content = msg.getOrDefault("content", "");
                if ("user".equals(role) && "新会话".equals(title)) {
                    title = content.length() > 20 ? content.substring(0, 20) + "…" : content;
                }
                if ("assistant".equals(role)) {
                    String cleaned = content.replaceAll("\\*\\*", "").replaceAll("\\n", " ").trim();
                    lastMessagePreview = cleaned.length() > 30 ? cleaned.substring(0, 30) + "…" : cleaned;
                }
                String at = msg.get("at");
                if (at != null) lastMessageAt = at;
            }

            ChatSessionDto dto = new ChatSessionDto();
            dto.setChatId(c.getId());
            dto.setCourseId(c.getCourseId());
            dto.setType("chat");
            dto.setTitle(title);
            dto.setMessageCount(messages.size());
            dto.setLastMessagePreview(lastMessagePreview);
            dto.setLastMessageAt(lastMessageAt);
            dto.setAnalyzed(c.getProfileVersionId() != null);
            dto.setProfileVersionId(c.getProfileVersionId());
            dto.setCreatedAt(c.getCreatedAt());
            result.add(dto);
        }

        // 2. Query tutoring_sessions (智能辅导)
        LambdaQueryWrapper<TutoringSession> tq = new LambdaQueryWrapper<>();
        tq.eq(TutoringSession::getUserId, userId)
          .eq(TutoringSession::getCourseId, courseId)
          .orderByDesc(TutoringSession::getCreatedAt);

        for (TutoringSession s : tutoringSessionMapper.selectList(tq)) {
            String title = s.getQuestion() != null
                ? (s.getQuestion().length() > 20 ? s.getQuestion().substring(0, 20) + "…" : s.getQuestion())
                : "智能辅导";
            String lastMessageAt = s.getCreatedAt() != null
                ? s.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : "";
            String lastMessagePreview = s.getQuestion() != null
                ? (s.getQuestion().length() > 30 ? s.getQuestion().substring(0, 30) + "…" : s.getQuestion())
                : "";

            ChatSessionDto dto = new ChatSessionDto();
            dto.setChatId(s.getId());
            dto.setCourseId(s.getCourseId());
            dto.setType("tutoring");
            dto.setTitle(title);
            dto.setMessageCount(0);
            dto.setLastMessagePreview(lastMessagePreview);
            dto.setLastMessageAt(lastMessageAt);
            dto.setAnalyzed(false);
            dto.setProfileVersionId(null);
            dto.setCreatedAt(s.getCreatedAt());
            result.add(dto);
        }

        // 3. Merge and sort by createdAt desc
        result.sort((a, b) -> {
            LocalDateTime ta = a.getCreatedAt();
            LocalDateTime tb = b.getCreatedAt();
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });

        return result;
    }

    @Override
    @Transactional
    public void deleteSession(String userId, String chatId) {
        ProfileChat chat = profileChatMapper.selectById(chatId);
        if (chat == null || !chat.getUserId().equals(userId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Chat session not found");
        }
        profileChatMapper.deleteById(chatId);
    }

    @Override
    public void endSession(String userId, String chatId) {
        ProfileChat chat = profileChatMapper.selectById(chatId);
        if (chat == null || !chat.getUserId().equals(userId)) {
            log.warn("endSession: chat not found or not owned by user, chatId={}, userId={}", chatId, userId);
            return;
        }
        List<Map<String, String>> messages = parseRawMessages(chat.getMessagesJson());
        if (messages == null || messages.isEmpty()) {
            log.info("endSession: chat has no messages, skipping, chatId={}", chatId);
            return;
        }
        profileService.handleChatEnd(userId, chat.getCourseId(), chatId, messages);
    }

    /**
     * 深度画像分析（同步阻塞版）
     * <p>
     * 职责：从完整对话记录中提取 7 维画像信息，并执行版本管理与防回退校验。
     * 注意：该方法涉及多次 LLM 调用与 DB 读写，建议仅在必要时由后台任务触发。
     *
     * @param userId 用户ID
     * @param chatId 会话ID
     * @return 包含版本号、摘要及维度详情的画像汇总对象
     */
    @Override
    @Transactional
    public ProfileSummaryDto analyzeProfile(String userId, String chatId) {
        ProfileChat chat = profileChatMapper.selectById(chatId);
        if (chat == null || !chat.getUserId().equals(userId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Chat session not found");
        }

        // v3: 改为从 DB 读取最新画像版本，不再调用 LLM
        ProfileVersion pv = profileVersionMapper.selectOne(
                new LambdaQueryWrapper<ProfileVersion>()
                        .eq(ProfileVersion::getUserId, userId)
                        .eq(ProfileVersion::getCourseId, chat.getCourseId())
                        .orderByDesc(ProfileVersion::getVersion)
                        .last("LIMIT 1"));

        if (pv == null) {
            log.info("No profile version found for userId={}, chatId={}", userId, chatId);
            return new ProfileSummaryDto(null, 0, Map.of(), Map.of("dimensions", List.of()));
        }

        log.info("analyzeProfile (DB read): userId={}, chatId={}, version={}",
                userId, chatId, pv.getVersion());

        return new ProfileSummaryDto(pv.getId(), pv.getVersion(), Map.of(),
                Map.of("dimensions", List.of()));
    }

    /**
     * 流式消息响应（SSE 编排核心）
     * <p>
     * 架构设计：
     * 1. Pre-stream：发射上下文感知（CONTEXT）、RAG 检索（RETRIEVE/RAG）等思考事件。
     * 2. Streaming：通过 AgentContext 桥接 LLM Token 流，实时推送内容块。
     * 3. Post-stream：评估画像充足度（REFLECT），拼装 DONE 事件，并触发异步 Delta 保存。
     *
     * @param userId  用户ID
     * @param chatId  会话ID
     * @param request 用户输入请求
     * @return 包含思考事件、文本块及完成信号的 Flux 流
     */
    @Override
    public Flux<SseEvent> streamMessage(String userId, String chatId, ChatSendRequest request) {
        log.info("=== 流式对话开始 === userId={}, chatId={}, content={}",
                userId, chatId, request.getContent().length() > 100 ? request.getContent().substring(0, 100) + "..." : request.getContent());

        ProfileChat chat = profileChatMapper.selectById(chatId);
        if (chat == null) {
            log.info("懒创建会话: chatId={}, userId={}, courseId={}", chatId, userId, request.getCourseId());
            chat = lazyCreateSession(chatId, userId, request.getCourseId());
        } else if (!chat.getUserId().equals(userId)) {
            log.warn("会话不存在或访问被拒绝: chatId={}, userId={}", chatId, userId);
            return Flux.error(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Chat session not found"));
        }
        final ProfileChat session = chat;

        List<Map<String, String>> messages = parseRawMessages(session.getMessagesJson());
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        messages.add(Map.of("role", "user", "content", request.getContent(), "at", now));
        log.info("用户消息已保存，当前消息数: {}", messages.size());

        chat.setMessagesJson(toJson(messages));
        profileChatMapper.updateById(chat);

        int roundNum = messages.size() / 2 + 1;
        log.info("对话轮次: {}", roundNum);

        // ── 在 LLM 调用前收集真实上下文数据 ──
        String courseName = getCourseName(chat.getCourseId());
        int profileCovered = getProfileCoveredCount(userId, chat.getCourseId());
        log.info("上下文信息 - 课程: {}, 画像覆盖: {}/7", courseName != null ? courseName : "未知", profileCovered);

        // ── 构建带有观察钩子的 AgentContext，桥接到 SSE ──
        // 观察者模式：CollectingObservation 负责捕获 Agent 内部决策点，并将其转换为前端可渲染的 SSE 思考事件。
        // 所有 agent.thought 事件均使用 ThinkingPhase 枚举，确保前后端阶段命名一致。
        /**
         * SSE事件收集观察者
         * <p>捕获 Agent 内部决策点并转换为前端可渲染的 SSE 思考事件。
         * onDecision 根据 decision 字符串自动推导 {@link ThinkingPhase} 枚举，
         * 确保前后端阶段命名一致。</p>
         */
        class CollectingObservation implements AgentObservation {
            /** 已发射的 SSE 事件列表 */
            final List<SseEvent> sseEvents = new ArrayList<>();
            /** 缓存的思考步骤数据（供异步持久化复用，避免重复构建步骤列表） */
            final List<Map<String, String>> capturedThoughts = new ArrayList<>();
            /** 结构化追踪数据（每阶段一行，用于 agent_thinking_traces 表逐行持久化） */
            final List<Map<String, String>> thinkingTraces = new ArrayList<>();

            @Override
            public void onPrompt(String agentName, String prompt, Map<String, Object> params) {
                // 内部调用，不发射为 agent.thought 事件
            }

            @Override
            public void onResponse(String agentName, String rawResponse, long elapsedMs, AgentResult.TokenUsage tokens) {
                emitThought(ThinkingPhase.DECISION, agentName, "conversation",
                    "LLM 回复生成完成",
                    "LLM 回复生成完成，耗时 " + elapsedMs + "ms",
                    rawResponse != null ? rawResponse : "流式生成正常",
                    "",
                    "high");
            }

            @Override
            public void onDecision(String agentName, String decision, String reason) {
                ThinkingPhase phase = derivePhase(decision);
                String context = buildPhaseContext(phase);
                String confidence = deriveConfidence(phase);
                String obs = reason != null ? reason : "";
                String thought = decision != null ? decision : "";
                String dec = phase == ThinkingPhase.DECISION && decision != null ? decision : "";
                emitThought(phase, agentName, "conversation", context, obs, thought, dec, confidence);
            }

            @Override
            public void onError(String agentName, Throwable error) {
                emitThought(ThinkingPhase.ERROR, agentName, "conversation",
                    "Agent 执行异常",
                    error.getMessage() != null ? error.getMessage() : "未知错误",
                    "Agent " + agentName + " 执行出错",
                    "",
                    "low");
            }

            /** 供外部代码（RagToolCallback 回调、PLANNING/REFLECT 发射点）直接写入思考事件。 */
            public void emitThought(ThinkingPhase phase, String agentName, String agentRole,
                                     String context, String observation, String thought,
                                     String decision, String confidenceLevel) {
                sseEvents.add(toSseEvent("agent.thought", Map.of(
                    "agentName", agentName,
                    "agentRole", agentRole,
                    "phase", phase.name(),
                    "context", context,
                    "observation", observation,
                    "thought", thought,
                    "decision", decision,
                    "confidenceLevel", confidenceLevel,
                    "timestamp", Instant.now().toString()
                )));
                Map<String, String> step = toStepData(phase, context, observation, thought, decision, confidenceLevel);
                if (step != null) {
                    capturedThoughts.add(step);
                }
                thinkingTraces.add(Map.of(
                    "agentName", agentName,
                    "agentRole", agentRole,
                    "phase", phase.name(),
                    "context", context != null ? context : "",
                    "observation", observation != null ? observation : "",
                    "thought", thought != null ? thought : "",
                    "decision", decision != null ? decision : "",
                    "confidenceLevel", confidenceLevel != null ? confidenceLevel : "medium"
                ));
            }

            /** 仅记录步骤数据到 capturedThoughts（不发射 SSE）。
             *  用于 SSE 时机由外部控制的场景（如 toolEventBuffer、preGenEvents），
             *  确保持久化数据与流式事件同步而不产生重复 SSE 发射。 */
            public void captureStep(ThinkingPhase phase, String context, String observation,
                                     String thought, String decision, String confidenceLevel) {
                Map<String, String> step = toStepData(phase, context, observation, thought, decision, confidenceLevel);
                if (step != null) {
                    capturedThoughts.add(step);
                }
                thinkingTraces.add(Map.of(
                    "agentName", "ConversationAgent",
                    "agentRole", "conversation",
                    "phase", phase.name(),
                    "context", context != null ? context : "",
                    "observation", observation != null ? observation : "",
                    "thought", thought != null ? thought : "",
                    "decision", decision != null ? decision : "",
                    "confidenceLevel", confidenceLevel != null ? confidenceLevel : "medium"
                ));
            }

            private ThinkingPhase derivePhase(String decision) {
                if (decision == null) return ThinkingPhase.DECISION;
                if (decision.startsWith("CONTEXT")) return ThinkingPhase.CONTEXT;
                if (decision.startsWith("RETRIEVE")) return ThinkingPhase.RETRIEVE;
                if (decision.startsWith("RAG")) return ThinkingPhase.RAG;
                return ThinkingPhase.DECISION;
            }

            private String deriveConfidence(ThinkingPhase phase) {
                switch (phase) {
                    case CONTEXT:
                    case REFLECT:
                        return profileCovered >= 4 ? "high" : "medium";
                    case DECISION:
                        return "high";
                    default:
                        return "medium";
                }
            }

            private String buildPhaseContext(ThinkingPhase phase) {
                switch (phase) {
                    case CONTEXT:
                        return "第" + roundNum + "轮对话"
                            + (courseName != null ? "，课程: " + courseName : "");
                    case DECISION:
                        return "LLM 回复生成完成";
                    case PLANNING:
                        return "意图分析与回复规划完成";
                    case REFLECT:
                        return "画像覆盖度评估，已覆盖 " + profileCovered + "/7 维度";
                    case RETRIEVE:
                        return "检测到知识性问题，LLM 决定检索课程知识库";
                    case RAG:
                        return "知识库检索完成";
                    case ERROR:
                        return "Agent 执行过程出现异常";
                    default:
                        return "";
                }
            }

            /** 将思考阶段转为持久化步骤数据（ThinkingStep JSON 子集，供 messages.thinking 字段使用） */
            private Map<String, String> toStepData(ThinkingPhase phase, String context, String observation,
                                                    String thought, String decision, String confidenceLevel) {
                String label = phaseLabel(phase);
                String icon = phaseIcon(phase);
                if (label == null) return null;
                StringBuilder detail = new StringBuilder();
                if (context != null && !context.isEmpty()) detail.append(context);
                if (observation != null && !observation.isEmpty()) {
                    if (detail.length() > 0) detail.append(" — ");
                    detail.append(observation);
                }
                Map<String, String> step = new LinkedHashMap<>();
                step.put("label", label);
                step.put("icon", icon);
                step.put("done", "true");
                step.put("phase", phase.name());
                step.put("detail", detail.toString());
                if (observation != null && !observation.isEmpty()) {
                    step.put("observation", observation);
                }
                if (thought != null && !thought.isEmpty()) {
                    step.put("thought", thought);
                }
                if (decision != null && !decision.isEmpty()) {
                    step.put("decision", decision);
                }
                if (confidenceLevel != null && !confidenceLevel.isEmpty()) {
                    step.put("confidenceLevel", confidenceLevel);
                }
                return step;
            }

            private String phaseLabel(ThinkingPhase phase) {
                switch (phase) {
                    case CONTEXT:  return "理解上下文";
                    case RETRIEVE: return "检索知识库";
                    case RAG:      return "检索分析";
                    case PLANNING: return "意图分析与回复规划";
                    case DECISION: return "决策判断";
                    case REFLECT:  return "评估画像";
                    default:       return null; // ERROR 等不持久化
                }
            }

            private String phaseIcon(ThinkingPhase phase) {
                switch (phase) {
                    case CONTEXT:  return "📋";
                    case RETRIEVE: return "🔗";
                    case RAG:      return "🔍";
                    case PLANNING: return "🔍";
                    case DECISION: return "⚖️";
                    case REFLECT:  return "🎯";
                    default:       return "●";
                }
            }
        }

        CollectingObservation chatObs = new CollectingObservation();
        AgentContext ctx = AgentContext.builder(chatId, userId)
            .courseId(chat.getCourseId())
            .observation(chatObs)
            .build();

        // ── 预流处理：模式 + 对话分支 ──
        // 说明：standard（Plan-then-Generate）模式下，“是否展示生成按钮/澄清文案”的决策
        // 统一由 ConversationPlanner 的 [CONTROL] 输出给出；ChatServiceImpl 不再做额外的意图探测。
        String chatMode = request.getMode() != null ? request.getMode() : "chat";
        boolean planMode = "plan".equals(chatMode);
        boolean unifiedMode = isUnifiedMode(request);

        // Unified 模式：传统路径（UnifiedGenerator 目前不产出 Planner 控制块）
        final ConversationAgent.GenerationIntent genIntent;
        final boolean hasGenIntent;
        final String preClarifying;
        if (unifiedMode) {
            ConversationAgent.GenerationIntent tmpIntent = null;
            boolean tmpHas = false;
            boolean skipIntent = Boolean.TRUE.equals(request.getSkipGenerationIntent());
            if (!skipIntent) {
                if ("resource".equals(chatMode)) {
                    tmpIntent = conversationAgent.detectGenerationIntent(request.getContent(), messages);
                    tmpHas = tmpIntent != null && tmpIntent.wantsGeneration();
                } else if (!planMode) {
                    tmpIntent = conversationAgent.detectGenerationIntent(request.getContent(), messages);
                    tmpHas = tmpIntent != null && tmpIntent.wantsGeneration();
                }
            }
            genIntent = tmpIntent;
            hasGenIntent = tmpHas;
            preClarifying = tmpHas ? conversationAgent.generateClarifyingQuestion(tmpIntent, null, courseName) : null;
        } else {
            genIntent = null;
            hasGenIntent = false;
            preClarifying = null;
        }

        // ── 构建预置事件：CONTEXT + RETRIEVE（在流式传输前发送）──
        // 预置思考事件：在 LLM 开始吐字前，先向客户端同步当前的上下文环境与 RAG 检索结果。
        String contextObs = (courseName != null ? "课程: " + courseName : "课程已选择")
            + (profileCovered > 0 ? "，画像已覆盖 " + profileCovered + "/7 维度" : "，画像尚未建立");
        String contextThought = profileCovered >= 4
            ? "已充分了解学生背景，结合画像深入理解问题"
            : "画像信息有限，从对话中尽力理解学生需求";

        chatObs.onDecision("ConversationAgent",
            "CONTEXT — 第" + roundNum + "轮对话，"
                + (profileCovered >= 4 ? "画像充足（确信度高）" : "画像信息有限（继续收集）"),
            contextObs);



        List<SseEvent> preEvents = new ArrayList<>(chatObs.sseEvents);

        // ── 用于收集流式 LLM Token 的缓冲区 ──
        StringBuilder replyBuffer = new StringBuilder();

        // ── ReAct: shared tool event buffer for dynamic SSE emission ──
        // LLM 自主决定是否调用 rag_retrieve 工具，工具调用期间的 RETRIEVE/RAG
        // 思考事件通过此缓冲区实时转发到 SSE 流。
        List<SseEvent> toolEventBuffer = Collections.synchronizedList(new ArrayList<>());

        // 累积 RAG 检索结果，后续注入 ReplyGenerator 的 {plan_result}
        StringBuilder ragContentAccum = new StringBuilder();
        ToolCallback ragCallback = null;
        if (chat.getCourseId() != null && !chat.getCourseId().isBlank()) {
            ragCallback = new RagToolCallback(ragTool, chat.getCourseId(),
                List.of(() -> {
                    ctx.put("rag_triggered", "true");
                    toolEventBuffer.add(
                        toSseEvent("agent.thought", Map.of(
                            "agentName", "ConversationAgent",
                            "agentRole", "conversation",
                            "phase", ThinkingPhase.RETRIEVE.name(),
                            "context", "检测到知识性问题，LLM 决定检索课程知识库获取准确资料",
                            "observation", "正在检索课程知识库...",
                            "thought", "LLM 自主调用 rag_retrieve 工具",
                            "decision", "",
                            "confidenceLevel", "high",
                            "timestamp", Instant.now().toString()
                        ))
                    );
                    chatObs.captureStep(ThinkingPhase.RETRIEVE,
                        "检测到知识性问题，LLM 决定检索课程知识库获取准确资料",
                        "正在检索课程知识库...", "LLM 自主调用 rag_retrieve 工具", "", "high");
                }),
                result -> {
                    try {
                        Map<String, Object> resultMap = objectMapper.readValue(result,
                            new TypeReference<Map<String, Object>>() {});
                        Object sources = resultMap.get("sources");
                        int count = (sources instanceof List) ? ((List<?>) sources).size() : 0;
                        ctx.put("rag_source_count", String.valueOf(count));
                        toolEventBuffer.add(toSseEvent("agent.thought", Map.of(
                            "agentName", "ConversationAgent",
                            "agentRole", "conversation",
                            "phase", ThinkingPhase.RAG.name(),
                            "context", "知识库检索完成，获取 " + count + " 条相关资料",
                            "observation", "检索到 " + count + " 条相关资料，LLM 将基于这些资料生成回答",
                            "thought", "RAG 检索完成并纳入回答上下文",
                            "decision", "",
                            "confidenceLevel", count >= 3 ? "high" : "medium",
                            "timestamp", Instant.now().toString()
                        )));
                        chatObs.captureStep(ThinkingPhase.RAG,
                            "知识库检索完成，获取 " + count + " 条相关资料",
                            "检索到 " + count + " 条相关资料，LLM 将基于这些资料生成回答",
                            "RAG 检索完成并纳入回答上下文", "", count >= 3 ? "high" : "medium");

                        // 累积 RAG 检索原始内容，后续传给 ReplyGenerator
                        if (sources instanceof List && !((List<?>) sources).isEmpty()) {
                            appendRagContent(ragContentAccum, (List<?>) sources);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse RAG result for SSE event: {}", e.getMessage());
                    }
                });
        }

        ctx.put("rag_tool", ragCallback);

        BookInfoToolCallback bookInfoCallback = new BookInfoToolCallback(bookInfoTool, chat.getCourseId());
        ctx.put("book_info_tool", bookInfoCallback);

        // ── 对话模式分支 — 确定主流式阶段 ──
        Flux<SseEvent> mainPhase;
        if (unifiedMode) {
            // 统一模式：单次 LLM 传递，包含思考 + 工具 + 回复生成
            UnifiedGenerator.UnifiedInput unifiedInput = new UnifiedGenerator.UnifiedInput(
                buildCourseContext(userId, chat.getCourseId()),
                buildProfileContext(userId, chat.getCourseId()),
                messages, roundNum, chatMode);

            mainPhase = unifiedGenerator.streamUnified(unifiedInput, ctx)
                .flatMap(chatResponse -> {
                    List<SseEvent> events = new ArrayList<>();
                    synchronized (toolEventBuffer) {
                        events.addAll(toolEventBuffer);
                        toolEventBuffer.clear();
                    }
                    String text = chatResponse.getResult() != null
                        ? chatResponse.getResult().getOutput().getText()
                        : null;
                    if (text != null && !text.isEmpty()) {
                        replyBuffer.append(text);
                        events.add(SseEvent.chunk(text));
                    }
                    return Flux.fromIterable(events);
                });
        } else {
            // 标准模式：规划-生成两阶段架构
            // ── 阶段 1：规划器 — 意图分析 + 回复规划 ──
            String modeHint = "";
            if ("resource".equals(chatMode)) {
                modeHint = "\n\n## 当前模式\n用户已切换至「资源生成」模式，请关注用户的资源需求。";
            } else if (planMode) {
                modeHint = "\n\n## 当前模式\n用户已切换至「学习规划」模式，请关注用户的学习目标和规划需求。";
            }
            String plannerPrompt = promptLoader.get("agent/planner_chat")
                .replace("{course_context}", buildCourseContext(userId, chat.getCourseId()))
                .replace("{profile_context}", buildProfileContext(userId, chat.getCourseId()));
            if (!modeHint.isBlank()) {
                plannerPrompt += modeHint;
            }
            ConversationPlanner.ConversationInput planInput =
                new ConversationPlanner.ConversationInput(chat.getCourseId(), messages, roundNum, plannerPrompt);

            StringBuilder plannerAccum = new StringBuilder();
            Flux<SseEvent> plannerPhase = conversationPlanner.streamPlan(planInput, ctx)
                .flatMap(chatResponse -> {
                    List<SseEvent> events = new ArrayList<>();
                    synchronized (toolEventBuffer) {
                        events.addAll(toolEventBuffer);
                        toolEventBuffer.clear();
                    }
                    String text = chatResponse.getResult() != null
                        ? chatResponse.getResult().getOutput().getText()
                        : null;
                    if (text != null && !text.isEmpty()) {
                        plannerAccum.append(text);
                    }
                    return Flux.fromIterable(events);
                });

            // ── 阶段 2：生成器 — 最终可见回复 ──
            Flux<SseEvent> genPhase = Flux.defer(() -> {
                String fullPlannerText = plannerAccum.toString();
                log.info("[AI-RESPONSE][ConversationPlanner] total={} chars\n{}",
                    fullPlannerText.length(),
                    fullPlannerText.substring(0, Math.min(3000, fullPlannerText.length())));

                String analysisText = extractAnalysis(fullPlannerText);
                String planContent = extractPlan(fullPlannerText);
                log.info("[AI-RESPONSE][ConversationPlanner] analysis={} chars, plan={} chars",
                    analysisText != null ? analysisText.length() : 0,
                    planContent != null ? planContent.length() : 0);

                ctx.put("planner_raw", fullPlannerText);

                List<SseEvent> preGenEvents = new ArrayList<>();
                String planningThought = planContent != null ? planContent : analysisText != null ? analysisText : fullPlannerText;
                if (planningThought != null && !planningThought.isEmpty()) {
                    preGenEvents.add(toSseEvent("agent.thought", Map.of(
                        "agentName", "ConversationPlanner",
                        "agentRole", "conversation",
                        "phase", ThinkingPhase.PLANNING.name(),
                        "context", "意图分析与回复规划完成",
                        "observation", "",
                        "thought", planningThought,
                        "decision", "",
                        "confidenceLevel", "high",
                        "timestamp", Instant.now().toString()
                    )));
                    chatObs.captureStep(ThinkingPhase.PLANNING,
                        "意图分析与回复规划完成", "",
                        planningThought, "", "high");
                }

                String lastUserMsg = messages.get(messages.size() - 1).get("content");
                String planResult = planContent != null ? planContent : fullPlannerText;
                if (ragContentAccum.length() > 0) {
                    planResult = planResult + "\n\n[参考课程资料]\n" + ragContentAccum + "\n[/参考课程资料]";
                }
                ReplyGenerator.GenInput genInput = new ReplyGenerator.GenInput(
                    buildCourseContext(userId, session.getCourseId()),
                    buildProfileContext(userId, session.getCourseId()),
                    planResult,
                    lastUserMsg);

                Flux<SseEvent> genStream = replyGenerator.streamReply(genInput)
                    .flatMap(chatResponse -> {
                        String text = chatResponse.getResult() != null
                            ? chatResponse.getResult().getOutput().getText()
                            : null;
                        if (text != null && !text.isEmpty()) {
                            replyBuffer.append(text);
                            return Flux.just(SseEvent.chunk(text));
                        }
                        return Flux.empty();
                    });

                return Flux.concat(Flux.fromIterable(preGenEvents), genStream);
            });

            mainPhase = plannerPhase.concatWith(genPhase);
        }

        // ── 后置流式处理：评估充足度，处理生成意图，保存，发送完成信号 ──
        // 后置处理：流式结束后进行画像充足度终审，并根据结果拼装生成引导语或 DONE 信号。
        Flux<SseEvent> postStream = Flux.defer(() -> {
            String fullReply = replyBuffer.length() > 0
                ? replyBuffer.toString()
                : "抱歉，我现在无法生成回复，请稍后再试。";

            log.info("回复内容:\n{}", fullReply);
            log.info("流式回复完成，长度: {} 字符", fullReply.length());

            // 收集后置观察事件（来自 onResponse 的 doFinally，在此 defer 之前已运行）
            List<SseEvent> postThoughtEvents = new ArrayList<>(chatObs.sseEvents);
            chatObs.sseEvents.clear();

            // 评估充足度（阻塞式 LLM 调用，在流式结束后运行）
            ConversationAgent.SufficiencyResult sufficiency;
            try {
                sufficiency = conversationAgent.evaluateSufficiency(messages);
            } catch (Exception e) {
                log.warn("充足度评估失败，使用默认值: {}", e.getMessage());
                sufficiency = new ConversationAgent.SufficiencyResult(false, 0, 0, List.of(), "");
            }

            log.info("画像充足度评估: sufficient={}, coveredCount={}/7, confidence={}",
                    sufficiency.sufficient(), sufficiency.coveredCount(),
                    String.format("%.0f%%", sufficiency.overallConfidence() * 100));

            // 生成按钮/澄清文案：
            // - standard 模式：优先使用 ConversationPlanner 的 [CONTROL] JSON（唯一权威来源）
            // - unified 模式：沿用 legacy 意图探测（UnifiedGenerator 暂不产出 [CONTROL]）
            boolean generationReady = false;
            Map<String, Object> generationMeta = null;
            boolean planGenerationReady = false;
            Map<String, Object> planGenerationMeta = null;
            StringBuilder extraText = new StringBuilder();

            // 从 planner 输出中解析 [CONTROL]（standard 模式使用 PlannerOutput；
            // unified 模式在 Phase 7 前回退到传统意图检测）
            PlannerOutput plannerOutput = null;
            if (!unifiedMode) {
                String plannerRaw = ctx.get("planner_raw");
                plannerOutput = PlannerOutput.fromPlannerText(plannerRaw);

                if (plannerOutput != null && plannerOutput.isGenerationReady()) {
                    generationReady = true;
                    ResourceRequirements req = plannerOutput.resourceRequirements();
                    generationMeta = new LinkedHashMap<>();
                    generationMeta.put("stage", "offered");
                    generationMeta.put("topic", req.topic());
                    generationMeta.put("goalSummary", req.goalSummary());
                    generationMeta.put("difficulty", req.difficulty());
                    if (req.items() != null && !req.items().isEmpty()) {
                        generationMeta.put("items", req.items().stream()
                            .map(item -> Map.of("type", item.type(), "focus", item.focus()))
                            .toList());
                    }
                    if (req.specialRequirements() != null) {
                        generationMeta.put("specialRequirements", req.specialRequirements());
                    }
                    generationMeta.put("coveredCount", sufficiency.coveredCount());
                    generationMeta.put("confidence", sufficiency.overallConfidence());
                }

                if (plannerOutput != null && plannerOutput.isPlanGenerationReady()) {
                    planGenerationReady = true;
                    var req = plannerOutput.planGenerationRequirements();
                    planGenerationMeta = new LinkedHashMap<>();
                    planGenerationMeta.put("stage", "offered");
                    planGenerationMeta.put("coveredCount", sufficiency.coveredCount());
                    if (req.requirementText() != null) {
                        planGenerationMeta.put("requirementText", req.requirementText());
                    }
                }

                if (plannerOutput == null) {
                    // 无有效 [CONTROL] 时不自动推荐生成，避免在普通对话中误弹方案卡片
                }
            } else {
                // Unified 模式：优先从完整回复中解析 [CONTROL]（Phase 7），
                // 回退到传统意图检测
                PlannerOutput unifiedPlanner = PlannerOutput.fromPlannerText(fullReply);
                if (unifiedPlanner != null && unifiedPlanner.isGenerationReady()) {
                    generationReady = true;
                    ResourceRequirements req = unifiedPlanner.resourceRequirements();
                    generationMeta = new LinkedHashMap<>();
                    generationMeta.put("stage", "offered");
                    generationMeta.put("topic", req.topic());
                    generationMeta.put("goalSummary", req.goalSummary());
                    generationMeta.put("difficulty", req.difficulty());
                    if (req.items() != null && !req.items().isEmpty()) {
                        generationMeta.put("items", req.items().stream()
                            .map(item -> Map.of("type", item.type(), "focus", item.focus()))
                            .toList());
                    }
                    if (req.specialRequirements() != null) {
                        generationMeta.put("specialRequirements", req.specialRequirements());
                    }
                    generationMeta.put("coveredCount", sufficiency.coveredCount());
                    generationMeta.put("confidence", sufficiency.overallConfidence());
                } else if (hasGenIntent) {
                    log.info("用户需要资源生成: chatId={}, prefs={}", chatId, genIntent.preferences());
                    String topic = conversationAgent.resolveTopic(
                        courseName != null ? courseName : "当前课程", messages, null);
                    java.util.Map<String, Object> prefsWithTopic =
                        new java.util.LinkedHashMap<>(genIntent.preferences());
                    prefsWithTopic.put("topic", topic);
                    if (preClarifying != null) {
                        extraText.append("\n\n").append(preClarifying);
                        generationReady = true;
                        generationMeta = Map.of("stage", "clarifying", "preferences", prefsWithTopic);
                    } else {
                        generationReady = true;
                        generationMeta = Map.of("stage", "ready", "preferences", prefsWithTopic);
                    }
                }

                // 不自动回退推荐生成：只有 LLM 明确输出 [CONTROL] 时才触发生成建议
            }

            // 保存助手消息到数据库（剥离 [CONTROL] 块）
            String cleanReply = PlannerOutput.stripControlBlock(fullReply);
            String aiAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            Map<String, String> asstMsg = new LinkedHashMap<>();
            asstMsg.put("role", "assistant");
            asstMsg.put("content", cleanReply);
            asstMsg.put("at", aiAt);
            // 将 planner 输出（分析 + 计划）与干净回复一同存储
            String plannerRaw = ctx.get("planner_raw");
            if (plannerRaw != null && !plannerRaw.isEmpty()) {
                asstMsg.put("planning", plannerRaw);
            }
            // 将方案建议（planOffer）嵌入消息持久化，前端刷新页面后可还原方案卡片
            if (generationReady && generationMeta != null) {
                Map<String, Object> planOffer = new LinkedHashMap<>();
                planOffer.put("type", "resource");
                planOffer.put("topic", generationMeta.getOrDefault("topic",
                    // fallback: extract topic from last user message
                    reverseFindUserMessage(messages)));
                planOffer.put("goalSummary", generationMeta.get("goalSummary"));
                planOffer.put("difficulty", generationMeta.get("difficulty"));
                planOffer.put("items", generationMeta.get("items"));
                planOffer.put("coveredCount", generationMeta.get("coveredCount"));
                planOffer.put("confidence", generationMeta.get("confidence"));
                planOffer.put("launchTopic", planOffer.get("topic"));
                asstMsg.put("planOffer", toJson(planOffer));
            }
            if (planGenerationReady && planGenerationMeta != null) {
                Map<String, Object> planOffer = new LinkedHashMap<>();
                planOffer.put("type", "plan");
                planOffer.put("requirementText", planGenerationMeta.getOrDefault("requirementText", ""));
                planOffer.put("coveredCount", planGenerationMeta.get("coveredCount"));
                String launchTopic = (String) planGenerationMeta.getOrDefault("topic",
                    reverseFindUserMessage(messages));
                planOffer.put("launchTopic", launchTopic);
                asstMsg.put("planOffer", toJson(planOffer));
            }
            messages.add(asstMsg);
            int asstMsgIdx = messages.size() - 1;
            session.setMessagesJson(toJson(messages));
            profileChatMapper.updateById(session);

            // 将 generationMeta 持久化到 Redis，供 TaskOrchestrator 在用户确认生成时使用
            // 这解决了聊天 planner 对用户意图的理解（estimatedCount、difficulty 等）
            // 在 POST /tasks/generate 时丢失的信息断层问题
            if (generationReady && generationMeta != null && chatId != null) {
                try {
                    redis.opsForValue().set("genmeta:" + chatId,
                        objectMapper.writeValueAsString(generationMeta),
                        Duration.ofHours(1));
                    log.info("Stored generationMeta to Redis for chatId={}", chatId);
                } catch (Exception e) {
                    log.warn("Failed to store generationMeta to Redis: {}", e.getMessage());
                }
            }

            // 构建后置流项目：缓冲的观察事件 + REFLECT 事件 + 额外文本 + 完成信号
            java.util.List<SseEvent> postItems = new java.util.ArrayList<>(postThoughtEvents);

            // 画像反射事件：向客户端同步最终的维度覆盖情况，作为本轮对话的阶段性总结
            int effectiveCovered = Math.max(profileCovered, sufficiency.coveredCount());
            int effectiveMissing = Math.max(0, 7 - effectiveCovered);
            String reflectMissing = effectiveMissing > 0 ? "，还缺" + effectiveMissing + "个维度" : "";
            String reflectObs = "已覆盖 " + effectiveCovered + "/7 维度" + reflectMissing;
            String reflectDecision = sufficiency.sufficient()
                ? "SUFFICIENT — 画像充足"
                : "CONTINUE — 继续收集画像信息";
            String reflectThoughtStr = "覆盖度 " + effectiveCovered
                + "/7，置信度 " + String.format("%.0f%%", sufficiency.overallConfidence() * 100);
            postItems.add(toSseEvent("agent.thought", Map.of(
                "agentName", "ConversationAgent",
                "agentRole", "conversation",
                "phase", ThinkingPhase.REFLECT.name(),
                "context", "画像覆盖度评估，已覆盖 " + effectiveCovered + "/7 维度",
                "observation", reflectObs,
                "thought", reflectThoughtStr,
                "decision", reflectDecision,
                "confidenceLevel", effectiveCovered >= 4 ? "high" : "medium",
                "timestamp", Instant.now().toString()
            )));
            chatObs.captureStep(ThinkingPhase.REFLECT,
                "画像覆盖度评估，已覆盖 " + effectiveCovered + "/7 维度",
                reflectObs, reflectThoughtStr, reflectDecision,
                effectiveCovered >= 4 ? "high" : "medium");

            if (extraText.length() > 0) {
                postItems.add(SseEvent.chunk(extraText.toString()));
            }
            Map<String, Object> doneData = new java.util.LinkedHashMap<>();
            doneData.put("profileReady", true);
            doneData.put("profileVersionId", "");
            doneData.put("coveredCount", effectiveCovered);
            doneData.put("generationReady", generationReady);
            doneData.put("generationMeta", generationMeta != null ? generationMeta : Map.of());
            doneData.put("planGenerationReady", planGenerationReady);
            doneData.put("planGenerationMeta", planGenerationMeta != null ? planGenerationMeta : Map.of());
            // Unified 回复计划（Phase 2+）：standard 模式使用 PlannerOutput，unified 使用兜底
            if (plannerOutput != null) {
                var rp = plannerOutput.replyPlan();
                doneData.put("replyPlan", Map.of(
                    "strategy", rp != null ? rp.strategy() : "direct_answer",
                    "shouldShowOffer", rp != null && rp.shouldShowOffer() && !plannerOutput.intent().needsClarification(),
                    "keyPoints", rp != null && rp.keyPoints() != null ? rp.keyPoints() : List.of(),
                    "tone", rp != null && rp.tone() != null ? rp.tone() : ""
                ));
            } else {
                doneData.put("replyPlan", Map.of(
                    "strategy", generationReady ? "offer_generation" : "direct_answer",
                    "shouldShowOffer", generationReady,
                    "keyPoints", List.of(),
                    "tone", ""
                ));
            }
            log.info(">>> SSE DONE event: generationReady={}, meta={}", generationReady,
                generationMeta != null ? generationMeta.get("stage") : "null");
            if (generationReady) {
                log.info(">>> SSE DONE meta details: {}", generationMeta);
            }
            postItems.add(toSseEvent("done", doneData));

            // ── Async: thinking trace + incremental delta save ──
            // 异步收尾：持久化思考轨迹（Thinking Trace）并执行画像增量更新（Delta Update），确保主线程快速释放。
            final String fChatId = chatId;
            final String fUserId = userId;
            final int fAsstMsgIdx = asstMsgIdx;
            final List<Map<String, String>> fMessages = new ArrayList<>(messages);
            final ConversationAgent.SufficiencyResult fSufficiency = sufficiency;
            final String fContextObs = contextObs;
            final String fCourseId = session.getCourseId();

            final List<Map<String, String>> fCapturedThoughts = new ArrayList<>(chatObs.capturedThoughts);
            final List<Map<String, String>> fThinkingTraces = new ArrayList<>(chatObs.thinkingTraces);
            final int fRoundNum = roundNum;

            CompletableFuture.runAsync(() -> {
                try {
                    // ── 持久化思考链（逐行写入 agent_thinking_traces） ──
                    // 每个 ThinkingPhase 独立一行，带 roundNum 标记供历史消息重建。
                    if (persistenceService != null) {
                        for (Map<String, String> trace : fThinkingTraces) {
                            try {
                                persistenceService.recordChatThinkingTrace(
                                    fChatId,
                                    trace.get("agentName"),
                                    trace.get("agentRole"),
                                    trace.get("phase"),
                                    trace.get("context"),
                                    trace.get("observation"),
                                    trace.get("thought"),
                                    trace.get("decision"),
                                    trace.get("confidenceLevel"),
                                    fRoundNum);
                            } catch (Exception inner) {
                                log.warn("单条思考链持久化失败 (phase={}): {}", trace.get("phase"), inner.getMessage());
                            }
                        }
                    }

                    // ── 持久化 thinking JSON（供前端历史恢复） ──
                    String thinkingJson = objectMapper.writeValueAsString(Map.of(
                        "steps", fCapturedThoughts,
                        "expanded", false
                    ));
                    messages.get(fAsstMsgIdx).put("thinking", thinkingJson);
                    session.setMessagesJson(toJson(messages));
                    profileChatMapper.updateById(session);

                    // 增量 delta 保存已移除，改为会话结束时触发两步流水线 (handleChatEnd)
                    // 注意：会话结束触发由 ChatController /{chatId}/end 端点或定时扫描器执行
                } catch (Exception e) {
                    log.warn("异步思考链持久化失败: {}", e.getMessage());
                }
            }, profileAnalysisExecutor);

            return Flux.fromIterable(postItems);
        });

        return Flux.fromIterable(preEvents)
            .concatWith(mainPhase
                .doOnError(e -> log.error("Main phase failed, continuing to post-stream: {}", e.getMessage()))
                .onErrorResume(e -> Flux.empty()))
            .concatWith(postStream);
    }

    private List<Message> buildChatMessages(List<Map<String, String>> messages, String userId, String courseId) {
        List<Message> chatMessages = new ArrayList<>();
        String systemPrompt = buildConversationSystemPrompt(userId, courseId);
        chatMessages.add(new SystemMessage(systemPrompt));
        for (var msg : messages) {
            String role = msg.get("role");
            String content = msg.get("content");
            if ("user".equals(role)) {
                chatMessages.add(new UserMessage(content));
            } else if ("assistant".equals(role) || "system".equals(role)) {
                chatMessages.add(new AssistantMessage(content));
            }
        }
        return chatMessages;
    }


    private String buildConversationSystemPrompt(String userId, String courseId) {
        String template = promptLoader.get("chat/profile_chat");
        String courseContext = buildCourseContext(userId, courseId);
        String profileContext = buildProfileContext(userId, courseId);
        return template
            .replace("{course_context}", courseContext)
            .replace("{profile_context}", profileContext);
    }

    private String getCourseName(String courseId) {
        if (courseId == null) return null;
        try {
            var course = courseMapper.selectById(courseId);
            return course != null ? course.getName() : null;
        } catch (Exception e) { return null; }
    }

    private int getProfileCoveredCount(String userId, String courseId) {
        if (userId == null || courseId == null) return 0;
        try {
            Profile profile = profileMapper.selectOne(
                new LambdaQueryWrapper<Profile>()
                    .eq(Profile::getUserId, userId).eq(Profile::getCourseId, courseId));
            if (profile == null || profile.getCurrentVersion() == null || profile.getCurrentVersion() == 0) return 0;
            ProfileVersion pv = profileVersionMapper.selectOne(
                new LambdaQueryWrapper<ProfileVersion>()
                    .eq(ProfileVersion::getUserId, userId).eq(ProfileVersion::getCourseId, courseId)
                    .eq(ProfileVersion::getVersion, profile.getCurrentVersion()));
            if (pv == null) return 0;
            int covered = 0;
            if (pv.getCoreProfileMd() != null && !pv.getCoreProfileMd().isBlank()) covered++;
            if (pv.getLearningProfileMd() != null && !pv.getLearningProfileMd().isBlank()) covered++;
            if (pv.getKnowledgeProfileMd() != null && !pv.getKnowledgeProfileMd().isBlank()) covered++;
            if (pv.getDisplayJson() != null && !pv.getDisplayJson().isBlank()) covered++;
            return covered;
        } catch (Exception e) { return 0; }
    }

    /** 从课程表与画像版本中提取课程上下文 */
    private String buildCourseContext(String userId, String courseId) {
        // 1. 如果没有课程ID，直接返回默认提示
        if (courseId == null) {
            return "暂无课程信息（学生尚未选择课程）";
        }

        // 2. 获取课程名称
        String courseName = null;
        try {
            var course = courseMapper.selectById(courseId);
            if (course != null && course.getName() != null) {
                courseName = course.getName();
            }
        } catch (Exception e) {
            log.warn("查找课程名称失败: {}", e.getMessage());
        }

        // 3. 构建章节概览
        String chapterSummary = buildChapterSummary(courseId);

        // 4. 组装最终结果：仅返回课程名称 + 章节结构，画像信息由 buildProfileContext() 提供
        StringBuilder resultSb = new StringBuilder();
        if (courseName != null) {
            resultSb.append("课程: ").append(courseName);
        } else {
            resultSb.append("课程ID: ").append(courseId);
        }
        resultSb.append("\n章节目录：" + chapterSummary);

        return resultSb.toString();
    }

    /**
     * 从 BookInfo.toc JSON 中提取一级章节标题，拼接为轻量概览。
     * 格式：\\n章节概览：Ch1 绪论 | Ch2 知识表示 | ...
     */
    private String buildChapterSummary(String courseId) {
        try {
            BookInfo bookInfo = bookInfoTool.resolveBookInfo(courseId);
            if (bookInfo == null || bookInfo.getToc() == null) return "";
            List<Map<String, Object>> tocList = objectMapper.readValue(bookInfo.getToc(),
                    new TypeReference<List<Map<String, Object>>>() {});
            if (tocList == null || tocList.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tocList.size(); i++) {
                Map<String, Object> node = tocList.get(i);
                String title = (String) node.getOrDefault("title", "");
                if (!title.isEmpty()) {
                    if (sb.isEmpty()) sb.append("\n章节概览：");
                    else sb.append(" | ");
                    sb.append(title);
                }
            }

            return sb.toString();
        } catch (Exception e) {
            log.warn("构建章节概览失败: {}", e.getMessage());
            return "";
        }
    }


    /**
     * 将 RAG 检索到的原始资料格式化为文本，注入 ReplyGenerator 的上下文。
     * 每条资料包含：章节标题、标题路径、内容摘录。
     */
    @SuppressWarnings("unchecked")
    private void appendRagContent(StringBuilder buf, List<?> sources) {
        int idx = 1;
        for (Object src : sources) {
            if (!(src instanceof Map)) continue;
            Map<String, Object> s = (Map<String, Object>) src;
            String chapter = s.getOrDefault("chapterTitle", "").toString();
            String heading = s.getOrDefault("headingPath", "").toString();
            String quote = s.getOrDefault("quote", "").toString();
            if (buf.length() > 0) buf.append("\n");
            buf.append("资料").append(idx++).append("：");
            if (!chapter.isEmpty()) buf.append("[").append(chapter).append("] ");
            if (!heading.isEmpty() && !heading.equals(chapter)) buf.append(heading).append(" — ");
            buf.append(quote);
        }
    }

    /** 构建已知画像上下文字符串（MD 驱动 + 待确认项注入） */
    private String buildProfileContext(String userId, String courseId) {
        if (userId == null || courseId == null) return "暂无画像数据，请从零开始了解学生";
        try {
            ProfileMdSet mdSet = profileService.getCurrentMd(userId, courseId);
            boolean hasCore = mdSet.getCoreProfileMd() != null && !mdSet.getCoreProfileMd().isBlank();
            boolean hasLearning = mdSet.getLearningProfileMd() != null && !mdSet.getLearningProfileMd().isBlank();
            boolean hasKnowledge = mdSet.getKnowledgeProfileMd() != null && !mdSet.getKnowledgeProfileMd().isBlank();

            if (!hasCore && !hasLearning && !hasKnowledge) {
                return "尚无画像数据。这是首次对话，请从基础信息开始了解学生。";
            }

            StringBuilder sb = new StringBuilder("=== 当前学生画像 ===\n\n");
            if (hasCore) sb.append("## 核心画像\n").append(mdSet.getCoreProfileMd()).append("\n\n");
            if (hasLearning) sb.append("## 学习风格画像\n").append(mdSet.getLearningProfileMd()).append("\n\n");
            if (hasKnowledge) sb.append("## 知识掌握画像\n").append(mdSet.getKnowledgeProfileMd()).append("\n\n");

            // 注入待确认推断项
            List<ProfileSignal> pendingItems = signalService.loadPendingConfirmations(userId, courseId);
            if (!pendingItems.isEmpty()) {
                sb.append("## 待确认的学生画像信息\n");
                sb.append("系统从对话中推断出以下信息，请在教学中自然地与学生确认（一次最多1-2项）：\n");
                for (ProfileSignal item : pendingItems) {
                    String dimLabel = item.getDimension() != null ? item.getDimension() : "未知维度";
                    sb.append("- （").append(dimLabel).append("）").append(item.getValue()).append("\n");
                }
                sb.append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            log.warn("构建画像上下文失败: {}", e.getMessage());
            return "画像数据读取失败，请继续对话了解学生";
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }

private ProfileChat lazyCreateSession(String chatId, String userId, String courseId) {
        ProfileChat chat = new ProfileChat();
        chat.setId(chatId);
        chat.setUserId(userId);
        chat.setCourseId(courseId != null ? courseId : "");
        chat.setMessagesJson("[]");
        profileChatMapper.insert(chat);
        log.info("Lazy-created chat session: chatId={}, userId={}, courseId={}", chatId, userId, courseId);
        return chat;
    }

    private ProfileChat findActiveSessionWithMessages(String userId, String courseId) {
        LambdaQueryWrapper<ProfileChat> q = new LambdaQueryWrapper<>();
        q.eq(ProfileChat::getUserId, userId)
         .eq(ProfileChat::getCourseId, courseId)
         .isNull(ProfileChat::getProfileVersionId)
         .isNotNull(ProfileChat::getMessagesJson)
         .ne(ProfileChat::getMessagesJson, "[]")
         .orderByDesc(ProfileChat::getCreatedAt)
         .last("LIMIT 1");
        return profileChatMapper.selectOne(q);
    }

    private ProfileChat findLatestNonEmptySession(String userId, String courseId) {
        LambdaQueryWrapper<ProfileChat> q = new LambdaQueryWrapper<>();
        q.eq(ProfileChat::getUserId, userId)
         .eq(ProfileChat::getCourseId, courseId)
         .isNotNull(ProfileChat::getMessagesJson)
         .ne(ProfileChat::getMessagesJson, "[]")
         .orderByDesc(ProfileChat::getCreatedAt)
         .last("LIMIT 1");
        return profileChatMapper.selectOne(q);
    }

    @SuppressWarnings("unchecked")
    private List<ChatMessageDto> parseMessages(String json) {
        List<Map<String, String>> raw = parseRawMessages(json);
        return raw.stream()
            .map(m -> {
                Object thinking = null;
                String thinkingStr = m.get("thinking");
                if (thinkingStr != null) {
                    try {
                        thinking = objectMapper.readValue(thinkingStr,
                            new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        // 忽略解析失败
                    }
                }
                Object planOffer = null;
                String planOfferStr = m.get("planOffer");
                if (planOfferStr != null && !planOfferStr.isBlank()) {
                    try {
                        planOffer = objectMapper.readValue(planOfferStr,
                            new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        // 忽略解析失败
                    }
                }
                return new ChatMessageDto(m.get("role"), m.get("content"), m.get("at"), thinking, planOffer);
            })
            .toList();
    }

    /** 从消息列表反向查找最后一条用户消息文本 */
    private String reverseFindUserMessage(List<Map<String, String>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, String> m = messages.get(i);
            if ("user".equals(m.get("role"))) {
                return m.get("content");
            }
        }
        return "";
    }

    private List<Map<String, String>> parseRawMessages(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            log.warn("解析消息 JSON 失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 创建带 JSON 数据的命名 SSE 事件 */
    private SseEvent toSseEvent(String eventType, Map<String, Object> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            return SseEvent.named(eventType, json);
        } catch (Exception e) {
            log.error("序列化 SSE 事件失败: {}", e.getMessage());
            return SseEvent.named(eventType, "{}");
        }
    }


    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("序列化为 JSON 失败: {}", e.getMessage());
            return "[]";
        }
    }

    private ChatStartResponse buildStartResponse(String chatId, String courseId,
                                                   List<ChatMessageDto> messages) {
        ProfileChat chat = profileChatMapper.selectById(chatId);
        return new ChatStartResponse(chatId, courseId, messages,
            chat != null && chat.getProfileVersionId() != null,
            chat != null ? chat.getProfileVersionId() : null);
    }

    /**
     * v4.0: 异步触发知识点（KP）锚定
     * <p>
     * 目的：将非结构化的画像维度映射到课程结构树中的具体节点，为路径规划提供量化支撑。
     */
    private void triggerKpAnchoring(String profileVersionId, String courseId,
                                    List<Map<String, Object>> dimensions) {
        ExecutorService kpExecutor = Executors.newSingleThreadExecutor();
        kpExecutor.submit(() -> {
            try {
                log.info("[KP锚定] 开始异步锚定 - pvId={}", profileVersionId);
                kpAnchorService.anchor(profileVersionId, courseId, dimensions);
                log.info("[KP锚定] 锚定完成 - pvId={}", profileVersionId);
            } catch (Exception e) {
                log.error("[KP锚定] 锚定失败 - pvId={}: {}", profileVersionId, e.getMessage(), e);
            } finally {
                kpExecutor.shutdown();
            }
        });
    }

    /** 统计包含至少一个非空值字段的维度数量 */
    private int countMeaningfulDimensions(List<Map<String, Object>> dimensions) {
        if (dimensions == null) return 0;
        int count = 0;
        for (var dim : dimensions) {
            Object value = dim.get("value");
            if (!(value instanceof Map<?, ?> dimValue)) continue;
            boolean hasMeaningful = false;
            for (Object fieldVal : dimValue.values()) {
                if (fieldVal == null) continue;
                if (fieldVal instanceof String s && !s.isBlank()) { hasMeaningful = true; break; }
                if (fieldVal instanceof List<?> l && !l.isEmpty()) { hasMeaningful = true; break; }
                if (fieldVal instanceof Number) { hasMeaningful = true; break; }
            }
            if (hasMeaningful) count++;
        }
        return count;
    }

    /**
     * 判断是否使用统一对话模式。
     * 优先级：请求级别覆盖 > 全局配置默认值。
     */
    private boolean isUnifiedMode(ChatSendRequest request) {
        if (request.getDialogueMode() != null && !request.getDialogueMode().isBlank()) {
            return "unified".equals(request.getDialogueMode());
        }
        return "unified".equals(learnThinkProperties.getDialogueMode());
    }

    /**
     * 从 planner 输出中提取 [ANALYSIS] 与 [PLAN] 标记之间的文本
     * @param text planner 原始输出
     * @return 分析文本，无匹配返回 null
     */
    private String extractAnalysis(String text) {
        if (text == null) return null;
        int analysisStart = text.indexOf("[ANALYSIS]");
        if (analysisStart < 0) return null;
        analysisStart += "[ANALYSIS]".length();
        // 跳过结尾换行符
        while (analysisStart < text.length() && (text.charAt(analysisStart) == '\n' || text.charAt(analysisStart) == '\r')) {
            analysisStart++;
        }
        int planStart = text.indexOf("[PLAN]", analysisStart);
        if (planStart < 0) return text.substring(analysisStart).trim();
        return text.substring(analysisStart, planStart).trim();
    }

    /**
     * 从 planner 输出中提取 [PLAN] 与 ---PLAN_END--- 标记之间的文本
     * @param text planner 原始输出
     * @return 计划文本，无匹配返回原文本
     */
    private String extractPlan(String text) {
        if (text == null) return null;
        int planStart = text.indexOf("[PLAN]");
        if (planStart < 0) return text.trim();
        planStart += "[PLAN]".length();
        while (planStart < text.length() && (text.charAt(planStart) == '\n' || text.charAt(planStart) == '\r')) {
            planStart++;
        }
        int planEnd = text.indexOf("---PLAN_END---", planStart);
        if (planEnd < 0) return text.substring(planStart).trim();
        return text.substring(planStart, planEnd).trim();
    }



    // ================================================================
    // v3.5: 思考链历史重建 — 从 agent_thinking_traces 恢复完整思考链
    // ================================================================

    /**
     * 遍历消息列表，对每条助手消息检测 thinking 是否缺失或不足（≤2步 或 缺少phase字段），
     * 若是则从 agent_thinking_traces 表按 chat_id + round_num 查询并重建。
     * <p>
     * 为避免 N+1 查询，一次查出该会话全部 traces，按 round_num 分组后匹配。
     */
    private void reconstructThinkingForMessages(List<ChatMessageDto> messages, String chatId) {
        // 一次查出该会话全部 traces
        List<AgentThinkingTrace> allTraces = persistenceService.findTracesByChatId(chatId);
        if (allTraces.isEmpty()) return;

        // 按 round_num 分组（round_num 可能为 null，跳过）
        Map<Integer, List<AgentThinkingTrace>> tracesByRound = new HashMap<>();
        for (AgentThinkingTrace t : allTraces) {
            if (t.getRoundNum() == null) continue;
            tracesByRound.computeIfAbsent(t.getRoundNum(), k -> new ArrayList<>()).add(t);
        }

        int roundNum = 0;
        for (ChatMessageDto msg : messages) {
            if ("assistant".equals(msg.getRole())) {
                roundNum++;
                Object thinking = msg.getThinking();
                if (thinking == null || isThinkingInsufficient(thinking)) {
                    List<AgentThinkingTrace> roundTraces = tracesByRound.get(roundNum);
                    if (roundTraces != null && !roundTraces.isEmpty()) {
                        Map<String, Object> reconstructed = buildThinkingFromTraces(roundTraces);
                        if (reconstructed != null) {
                            msg.setThinking(reconstructed);
                        }
                    }
                }
            }
        }
    }

    /**
     * 判断 thinking 数据是否"不足"：
     * - null → 不足
     * - steps ≤ 2 → 不足（同步路径的固定文本）
     * - steps 缺少 phase 字段 → 不足（旧格式，无观察/思考/决策独立字段）
     */
    private boolean isThinkingInsufficient(Object thinking) {
        if (thinking == null) return true;
        if (thinking instanceof Map<?, ?> map) {
            Object steps = map.get("steps");
            if (steps instanceof List<?> list) {
                if (list.size() <= 2) return true;
                // 检查步骤是否缺少 phase 字段（旧格式标记）
                for (Object item : list) {
                    if (item instanceof Map<?, ?> step) {
                        if (!step.containsKey("phase")) return true;
                    }
                }
                return false;
            }
            return true;
        }
        return true;
    }

    /** 将 agent_thinking_traces 行记录重建为 ThinkingRecord 格式的 Map */
    private Map<String, Object> buildThinkingFromTraces(List<AgentThinkingTrace> traces) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (AgentThinkingTrace trace : traces) {
            ThinkingPhase phase = ThinkingPhase.fromString(trace.getPhase());
            String label = resolvePhaseLabel(phase);
            String icon = resolvePhaseIcon(phase);
            if (label == null) continue;

            Map<String, Object> step = new LinkedHashMap<>();
            step.put("label", label);
            step.put("icon", icon);
            step.put("done", true);
            if (phase != null) {
                step.put("phase", phase.name());
            }

            // detail: context + observation 的简洁摘要（折叠状态显示）
            StringBuilder detail = new StringBuilder();
            if (trace.getContext() != null && !trace.getContext().isEmpty()) {
                detail.append(trace.getContext());
            }
            if (trace.getObservation() != null && !trace.getObservation().isEmpty()) {
                if (detail.length() > 0) detail.append(" — ");
                detail.append(trace.getObservation());
            }
            if (detail.length() > 0) {
                step.put("detail", detail.toString());
            }

            // observation / thought / decision — 独立字段，前端渲染推理卡片用
            // 与 SSE agent.thought 事件结构一致：stepCategory() 据此分类为 reasoning/tool_call
            if (trace.getObservation() != null && !trace.getObservation().isEmpty()) {
                step.put("observation", trace.getObservation());
            }
            if (trace.getThought() != null && !trace.getThought().isEmpty()) {
                step.put("thought", trace.getThought());
            }
            if (trace.getDecision() != null && !trace.getDecision().isEmpty()) {
                step.put("decision", trace.getDecision());
            }
            if (trace.getConfidenceLevel() != null) {
                step.put("confidenceLevel", trace.getConfidenceLevel());
            }

            steps.add(step);
        }
        if (steps.isEmpty()) return null;

        Map<String, Object> thinking = new LinkedHashMap<>();
        thinking.put("steps", steps);
        thinking.put("expanded", false);
        return thinking;
    }

    private String resolvePhaseLabel(ThinkingPhase phase) {
        if (phase == null) return null;
        switch (phase) {
            case CONTEXT:  return "理解上下文";
            case RETRIEVE: return "检索知识库";
            case RAG:      return "检索分析";
            case PLANNING: return "意图分析与回复规划";
            case DECISION: return "决策判断";
            case REFLECT:  return "评估画像";
            default:       return null;
        }
    }

    private String resolvePhaseIcon(ThinkingPhase phase) {
        if (phase == null) return null;
        switch (phase) {
            case CONTEXT:  return "📋";
            case RETRIEVE: return "🔗";
            case RAG:      return "🔍";
            case PLANNING: return "🔍";
            case DECISION: return "⚖️";
            case REFLECT:  return "🎯";
            default:       return "●";
        }
    }

    /**
     * Insert a ProfileVersion row with automatic retry on duplicate version number.
     * <p>
     * The ReentrantLock serializes in-JVM access, but the @Transactional commit
     * happens AFTER the lock is released. A concurrent request may have already
     * committed the same version number by the time this insert executes.
     * This method catches DuplicateKeyException, re-reads the latest version
     * from the profile table, and retries.
     */
    @Deprecated
    private ProfileVersion insertProfileVersionWithRetry(
            String userId, String courseId, int initialVersion,
            List<Map<String, Object>> dimensions, Map<String, Object> summary,
            String chatId, int maxRetries) {
        throw new UnsupportedOperationException("Removed in v3 — use ProfileServiceImpl.persistProfileVersion instead");
    }
}