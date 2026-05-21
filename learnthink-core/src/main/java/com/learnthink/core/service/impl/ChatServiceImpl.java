package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.chat.*;
import com.learnthink.core.agent.framework.AgentContext;
import com.learnthink.core.agent.framework.AgentObservation;
import com.learnthink.core.agent.framework.AgentResult;
import com.learnthink.core.agent.impl.ConversationAgent;
import com.learnthink.core.agent.impl.RagTool;
import com.learnthink.core.agent.impl.ConversationPlanner;
import com.learnthink.core.agent.impl.RagToolCallback;
import com.learnthink.core.agent.impl.ReplyGenerator;
import com.learnthink.core.config.PromptLoader;
import com.learnthink.core.domain.entity.Profile;
import com.learnthink.core.domain.entity.ProfileChat;
import com.learnthink.core.domain.entity.ProfileVersion;
import com.learnthink.core.domain.entity.ResourceItem;
import com.learnthink.core.domain.entity.ResourcePack;
import com.learnthink.core.domain.entity.Task;
import com.learnthink.core.repository.CourseMapper;
import com.learnthink.core.repository.ProfileChatMapper;
import com.learnthink.core.repository.ProfileMapper;
import com.learnthink.core.repository.ProfileVersionMapper;
import com.learnthink.core.repository.ResourceItemMapper;
import com.learnthink.core.repository.ResourcePackMapper;
import com.learnthink.core.repository.TaskMapper;
import com.learnthink.core.service.ChatService;
import com.learnthink.core.service.KpAnchorService;
import com.learnthink.core.service.ProfileService;
import com.learnthink.core.service.TaskPersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;

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
    private final ResourcePackMapper resourcePackMapper;
    private final ResourceItemMapper resourceItemMapper;
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final PromptLoader promptLoader;
    private final TaskPersistenceService persistenceService;
    private final ProfileService profileService;
    private final ConversationAgent conversationAgent;
    private final RagTool ragTool;
    private final ConversationPlanner conversationPlanner;
    private final ReplyGenerator replyGenerator;
    private final KpAnchorService kpAnchorService;
    private final ExecutorService profileAnalysisExecutor = Executors.newFixedThreadPool(2);

    public ChatServiceImpl(ProfileChatMapper profileChatMapper,
                           ProfileMapper profileMapper,
                           ProfileVersionMapper profileVersionMapper,
                           CourseMapper courseMapper,
                           TaskMapper taskMapper,
                           ResourcePackMapper resourcePackMapper,
                           ResourceItemMapper resourceItemMapper,
                           @Qualifier("chatChatClientBuilder") ChatClient.Builder chatClientBuilder,
                           ObjectMapper objectMapper,
                           PromptLoader promptLoader,
                           TaskPersistenceService persistenceService,
                           ProfileService profileService,
                           ConversationAgent conversationAgent,
                           RagTool ragTool,
                           KpAnchorService kpAnchorService,
                           ConversationPlanner conversationPlanner,
                           ReplyGenerator replyGenerator) {
        this.profileChatMapper = profileChatMapper;
        this.profileMapper = profileMapper;
        this.profileVersionMapper = profileVersionMapper;
        this.courseMapper = courseMapper;
        this.taskMapper = taskMapper;
        this.resourcePackMapper = resourcePackMapper;
        this.resourceItemMapper = resourceItemMapper;
        this.chatClientBuilder = chatClientBuilder;
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
        this.persistenceService = persistenceService;
        this.profileService = profileService;
        this.conversationAgent = conversationAgent;
        this.ragTool = ragTool;
        this.kpAnchorService = kpAnchorService;
        this.conversationPlanner = conversationPlanner;
        this.replyGenerator = replyGenerator;
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
     * @param request 消息内容及模式信息
     * @return 包含 AI 回复、画像状态及生成就绪标识的响应
     */
    @Override
    @Transactional
    public ChatSendResponse sendMessage(String userId, String chatId, ChatSendRequest request) {
        ProfileChat chat = profileChatMapper.selectById(chatId);
        if (chat == null) {
            chat = lazyCreateSession(chatId, userId, request.getCourseId());
        } else if (!chat.getUserId().equals(userId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Chat session not found");
        }

        List<Map<String, String>> messages = parseRawMessages(chat.getMessagesJson());

        // 追加用户消息
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        messages.add(Map.of("role", "user", "content", request.getContent(), "at", now));

        // === Step A: Detect generation intent BEFORE LLM call ===
        // 意图探测：在调用 LLM 前通过关键词/语义判断用户是否需要生成学习资源，避免无效推理。
        String chatMode = request.getMode() != null ? request.getMode() : "chat";
        boolean planMode = "plan".equals(chatMode);

        ConversationAgent.GenerationIntent genIntent;
        boolean hasGenIntent;
        if ("resource".equals(chatMode)) {
            genIntent = conversationAgent.detectGenerationIntent(request.getContent(), messages);
            hasGenIntent = genIntent != null && genIntent.wantsGeneration();
        } else if (!planMode) {
            genIntent = conversationAgent.detectGenerationIntent(request.getContent(), messages);
            hasGenIntent = genIntent != null && genIntent.wantsGeneration();
        } else {
            genIntent = null;
            hasGenIntent = false;
        }

        String systemPrompt = buildConversationSystemPrompt(userId, chat.getCourseId());
        String modeHint = "";
        if ("resource".equals(chatMode)) {
            modeHint = "\n\n## 当前模式\n用户已切换至「资源生成」模式，请关注用户的资源需求。";
        } else if (planMode) {
            modeHint = "\n\n## 当前模式\n用户已切换至「学习规划」模式，请关注用户的学习目标和规划需求。";
        }
        // 如果用户想要资源生成，保留提示上下文但添加生成指令
        if (hasGenIntent) {
            systemPrompt = systemPrompt + modeHint + "\n\n## 当前请求\n用户请求生成学习资源。请用1-2句话简单确认（可引用课程和画像信息），然后询问具体需求。不要展开讲解任何知识点。";
        } else {
            systemPrompt = systemPrompt + modeHint;
        }

        int roundNum = messages.size() / 2 + 1;
        AgentContext ctx = AgentContext.builder(chatId, userId)
            .courseId(chat.getCourseId())
            .build();

        // ReAct: pass rag_retrieve tool via context so LLM decides when to search the KB
        ToolCallback ragCallback = null;
        if (chat.getCourseId() != null && !chat.getCourseId().isBlank()) {
            ragCallback = new RagToolCallback(ragTool, chat.getCourseId(), null, null);
        }
        ctx.put("rag_tool", ragCallback);
        var convResult = conversationAgent.execute(
            new ConversationAgent.ConversationInput(chat.getCourseId(), messages, roundNum, systemPrompt),
            ctx);

        String aiResponse = convResult.success()
            ? convResult.output().reply()
            : "抱歉，我现在无法生成回复，请稍后再试。";
        
        log.info("========== AI回复（同步模式） ==========");
        log.info("chatId: {}", chatId);
        log.info("userId: {}", userId);
        log.info("轮次: {}", roundNum);
        log.info("回复长度: {} 字符", aiResponse.length());
        log.info("回复内容:\n{}", aiResponse);
        log.info("========================================");
        
        ConversationAgent.SufficiencyResult sufficiency = convResult.success()
            ? convResult.output().sufficiency()
            : new ConversationAgent.SufficiencyResult(false, 0, 0, List.of(), "");

        // v3.1: 结构化充足度评估（由 ConversationAgent 提供）
        boolean profileReady = sufficiency.sufficient();
        String profileVersionId = null;
        boolean generationReady = false;
        Map<String, Object> generationMeta = null;

        if (hasGenIntent) {
            log.info("用户需要资源生成: chatId={}, prefs={}", chatId, genIntent.preferences());

            String clarifying = conversationAgent.generateClarifyingQuestion(genIntent, sufficiency,
                getCourseName(chat.getCourseId()));
            if (clarifying != null) {
                aiResponse = aiResponse + "\n\n" + clarifying;
                generationReady = true;
                generationMeta = Map.of("stage", "clarifying", "preferences", genIntent.preferences());
            } else {
                generationReady = true;
                generationMeta = Map.of("stage", "ready", "preferences", genIntent.preferences());

                // Async: trigger profile analysis + resource generation
                // 异步触发画像分析与资源生成流水线，使用独立线程池隔离耗时操作。
                String finalChatId = chatId;
                String finalUserId = userId;
                CompletableFuture.runAsync(() -> {
                    try {
                        ProfileSummaryDto summary = analyzeProfile(finalUserId, finalChatId);
                        ProfileChat pc = profileChatMapper.selectById(finalChatId);
                        if (pc != null) {
                            pc.setProfileVersionId(summary.getProfileVersionId());
                            profileChatMapper.updateById(pc);
                        }
                        log.info("生成流水线：画像已就绪, versionId={}", summary.getProfileVersionId());

                        // 占位：触发 TaskOrchestrator.createTask()
                        log.info("生成流水线：将为用户 {} 触发任务创建", finalUserId);
                    } catch (Exception e) {
                        log.error("生成流水线失败: {}", e.getMessage());
                    }
                }, profileAnalysisExecutor);
            }
        }

        // === Step B: 若画像刚达到充足标准，主动提供资源生成选项 ===
        if (profileReady && !hasGenIntent) {
            String offer = conversationAgent.generateResourceOffer(sufficiency);
            aiResponse = aiResponse + offer;
            generationReady = true;
            generationMeta = Map.of("stage", "offered",
                "coveredCount", sufficiency.coveredCount(),
                "confidence", sufficiency.overallConfidence());

            // Async profile analysis (non-blocking)
            // 异步画像分析：当画像达到充足标准时，后台增量更新维度数据，不阻塞主对话流。
            String finalChatId = chatId;
            String finalUserId = userId;
            CompletableFuture.runAsync(() -> {
                try {
                    ProfileSummaryDto summary = analyzeProfile(finalUserId, finalChatId);
                    ProfileChat pc = profileChatMapper.selectById(finalChatId);
                    if (pc != null) {
                        pc.setProfileVersionId(summary.getProfileVersionId());
                        profileChatMapper.updateById(pc);
                    }
                    log.info("Async profile analysis complete: chatId={}", finalChatId);
                } catch (Exception e) {
                    log.error("异步画像分析失败: {}", e.getMessage());
                }
            }, profileAnalysisExecutor);

            if (persistenceService != null) {
                try {
                    persistenceService.recordThinkingTrace(
                        chatId, "ConversationAgent", "conversation",
                        "CONVERSATION",
                        "对话第" + (messages.size() / 2) + "轮，评估信息覆盖度",
                        "已覆盖 " + sufficiency.coveredCount() + " 个维度，" +
                            "置信度 " + String.format("%.0f%%", sufficiency.overallConfidence() * 100),
                        "SUFFICIENT — 画像充足，向用户提供资源生成选项",
                        sufficiency.coveredCount() >= 6 ? "high" : "medium");
                } catch (Exception e) {
                    log.warn("持久化思考轨迹失败: {}", e.getMessage());
                }
            }
        }

        String aiAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        messages.add(Map.of("role", "assistant", "content", aiResponse, "at", aiAt));

        // 保存会话状态
        chat.setMessagesJson(toJson(messages));
        profileChatMapper.updateById(chat);

        List<ChatMessageDto> newMessages = List.of(
            new ChatMessageDto("assistant", aiResponse, aiAt, null));

        return new ChatSendResponse(chatId, newMessages, profileReady, profileVersionId, generationReady, generationMeta);
    }

    @Override
    public ChatMessagesResponse getMessages(String userId, String chatId) {
        ProfileChat chat = profileChatMapper.selectById(chatId);
        if (chat == null || !chat.getUserId().equals(userId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Chat session not found");
        }
        List<ChatMessageDto> messages = parseMessages(chat.getMessagesJson());

        // 查询关联的活跃任务
        List<Task> activeTasks = taskMapper.findByChatId(chatId);
        List<ActiveTaskDto> activeTaskDtos = new ArrayList<>();
        for (Task task : activeTasks) {
            ActiveTaskDto dto = new ActiveTaskDto();
            dto.setTaskId(task.getId());
            dto.setTopic(task.getTopic());
            dto.setStatus(task.getStatus());
            dto.setStage(task.getStage());
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

        // 检查生成就绪且无活跃任务（重建引导提示）
        boolean generationReady = chat.getProfileVersionId() != null && activeTaskDtos.isEmpty();
        Map<String, Object> generationMeta = null;
        if (generationReady) {
            generationMeta = Map.of("stage", "offered");
        }

        return new ChatMessagesResponse(messages, generationReady, generationMeta, false, Map.of(), activeTaskDtos);
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
        LambdaQueryWrapper<ProfileChat> q = new LambdaQueryWrapper<>();
        q.eq(ProfileChat::getUserId, userId)
         .eq(ProfileChat::getCourseId, courseId)
         .orderByDesc(ProfileChat::getCreatedAt);

        return profileChatMapper.selectList(q).stream()
            .map(c -> {
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

                return new ChatSessionDto(
                    c.getId(), c.getCourseId(), title, messages.size(),
                    lastMessagePreview, lastMessageAt,
                    c.getProfileVersionId() != null, c.getProfileVersionId(), c.getCreatedAt());
            })
            .toList();
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
        var lock = ProfileServiceImpl.getProfileLock(userId, chat.getCourseId());
        lock.lock();
        try {
            List<Map<String, String>> messages = parseRawMessages(chat.getMessagesJson());

            // 构建对话转录文本用于分析
            StringBuilder transcript = new StringBuilder();
            for (var msg : messages) {
                String role = msg.get("role");
                if ("user".equals(role) || "assistant".equals(role) || "system".equals(role)) {
                    transcript.append(role).append(": ").append(msg.get("content")).append("\n");
                }
            }

            // 调用 LLM 进行画像提取
            ChatClient client = chatClientBuilder.build();
            String response = client.prompt()
                .messages(
                    new SystemMessage(promptLoader.get("chat/profile_analysis")),
                    new UserMessage("对话记录：\n" + transcript)
                )
                .call()
                .content();

            // 解析 JSON 响应
            String json = response;
            if (json.contains("```json")) {
                json = json.substring(json.indexOf("```json") + 7, json.lastIndexOf("```"));
            } else if (json.contains("```")) {
                json = json.substring(json.indexOf("```") + 3, json.lastIndexOf("```"));
            }
            json = json.trim();

            Map<String, Object> analysis = objectMapper.readValue(json,
                new TypeReference<Map<String, Object>>() {});

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> dimensions = (List<Map<String, Object>>) analysis.get("dimensions");

            // Validate: don't overwrite a good profile with a worse analysis
            // 防回退校验：确保新提取的有效维度数不低于当前版本，防止画像质量波动。
            int newMeaningfulCount = countMeaningfulDimensions(dimensions);
            Profile existingProfile = profileMapper.selectOne(
                new LambdaQueryWrapper<Profile>()
                    .eq(Profile::getUserId, userId)
                    .eq(Profile::getCourseId, chat.getCourseId()));
            if (existingProfile != null && existingProfile.getCurrentVersion() != null && existingProfile.getCurrentVersion() > 0) {
                ProfileVersion currentPv = profileVersionMapper.selectOne(
                    new LambdaQueryWrapper<ProfileVersion>()
                        .eq(ProfileVersion::getUserId, userId)
                        .eq(ProfileVersion::getCourseId, chat.getCourseId())
                        .eq(ProfileVersion::getVersion, existingProfile.getCurrentVersion()));
                if (currentPv != null && currentPv.getDimensionsJson() != null) {
                    try {
                        List<Map<String, Object>> currentDims = objectMapper.readValue(
                            currentPv.getDimensionsJson(),
                            new TypeReference<List<Map<String, Object>>>() {});
                        int currentMeaningfulCount = countMeaningfulDimensions(currentDims);
                        if (newMeaningfulCount < currentMeaningfulCount) {
                            log.warn("跳过画像更新：新分析覆盖 {} 个有效维度，当前版本覆盖 {} 个",
                                newMeaningfulCount, currentMeaningfulCount);
                            return new ProfileSummaryDto(currentPv.getId(),
                                existingProfile.getCurrentVersion(),
                                objectMapper.readValue(currentPv.getSummaryJson(), Map.class),
                                Map.of("dimensions", currentDims));
                        }
                    } catch (Exception e) {
                        log.warn("无法解析当前画像版本，进行覆盖: {}", e.getMessage());
                    }
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> summary = (Map<String, Object>) analysis.get("summary");

            Profile profile = existingProfile;

            int newVersion;
            if (profile == null) {
                profile = new Profile();
                profile.setUserId(userId);
                profile.setCourseId(chat.getCourseId());
                newVersion = 1;
                profile.setCurrentVersion(newVersion);
                profileMapper.insert(profile);
            } else {
                newVersion = profile.getCurrentVersion() + 1;
                profile.setCurrentVersion(newVersion);
                profileMapper.updateById(profile);
            }

            ProfileVersion pv = new ProfileVersion();
            pv.setUserId(userId);
            pv.setCourseId(chat.getCourseId());
            pv.setVersion(newVersion);
            pv.setDimensionsJson(objectMapper.writeValueAsString(dimensions));
            pv.setSummaryJson(objectMapper.writeValueAsString(summary));
            pv.setSourceChatIds(objectMapper.writeValueAsString(List.of(chatId)));
            profileVersionMapper.insert(pv);

            chat.setProfileVersionId(pv.getId());
            profileChatMapper.updateById(chat);

            if (persistenceService != null && dimensions != null) {
                try {
                    int dimCount = dimensions.size();
                    Object confMap = summary.get("confidence");
                    persistenceService.recordThinkingTrace(
                        chatId, "ProfileAnalyzer", "profile",
                        "PROFILING",
                        "从 " + (messages != null ? messages.size() : 0) + " 轮对话中提取画像",
                        "已提取 " + dimCount + " 个维度，置信度 " + confMap,
                        "画像版本 " + newVersion + " 已生成",
                        dimCount >= 6 ? "high" : "medium");
                } catch (Exception e) {
                    log.warn("持久化画像思考轨迹失败: {}", e.getMessage());
                }
            }

            // v4.0: Trigger async KP anchoring
            // 知识点锚定：将画像维度映射到课程具体的知识点节点，支持后续精准推荐。
            triggerKpAnchoring(pv.getId(), chat.getCourseId(), dimensions);

            return new ProfileSummaryDto(pv.getId(), newVersion, summary,
                Map.of("dimensions", dimensions));

        } catch (Exception e) {
            log.error("Profile analysis failed for userId={}, chatId={}: {}", userId, chatId, e.getMessage());
            throw new RuntimeException("Profile analysis failed", e);
        } finally {
            lock.unlock();
        }
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
        class CollectingObservation implements AgentObservation {
            final List<SseEvent> sseEvents = new ArrayList<>();

            @Override
            public void onPrompt(String agentName, String prompt, Map<String, Object> params) {
                // 内部调用，不发射为 agent.thought 事件
            }

            @Override
            public void onResponse(String agentName, String rawResponse, long elapsedMs, AgentResult.TokenUsage tokens) {
                sseEvents.add(toSseEvent("agent.thought", Map.of(
                    "agentName", agentName,
                    "agentRole", "conversation",
                    "phase", "DECISION",
                    "context", "LLM 回复生成完成",
                    "observation", "LLM 回复生成完成，耗时 " + elapsedMs + "ms",
                    "thought", rawResponse != null ? rawResponse : "流式生成正常",
                    "decision", "",
                    "confidenceLevel", "high",
                    "timestamp", Instant.now().toString()
                )));
            }

            @Override
            public void onDecision(String agentName, String decision, String reason) {
                String phase;
                if (decision == null) phase = "DECISION";
                else if (decision.startsWith("CONTEXT")) phase = "CONTEXT";
                else if (decision.startsWith("RETRIEVE")) phase = "RETRIEVE";
                else if (decision.startsWith("RAG")) phase = "RAG";
                else phase = "DECISION";

                // 从实际数据推导置信度，而非硬编码
                String confidenceLevel = deriveConfidence(phase);
                // 使用真实数据构建上下文字符串
                String context = buildContext(phase);

                boolean isRealDecision = "DECISION".equals(phase);
                sseEvents.add(toSseEvent("agent.thought", Map.of(
                    "agentName", agentName,
                    "agentRole", "conversation",
                    "phase", phase,
                    "context", context,
                    "observation", reason != null ? reason : "",
                    "thought", decision != null ? decision : "",
                    "decision", isRealDecision && decision != null ? decision : "",
                    "confidenceLevel", confidenceLevel,
                    "timestamp", Instant.now().toString()
                )));
            }

            private String deriveConfidence(String phase) {
                switch (phase) {
                    case "CONTEXT":
                        return profileCovered >= 4 ? "high" : "medium";
                    case "DECISION":
                        return "high";
                    default:
                        return "medium";
                }
            }

            private String buildContext(String phase) {
                switch (phase) {
                    case "CONTEXT":
                        return "第" + roundNum + "轮对话"
                            + (courseName != null ? "，课程: " + courseName : "");
                    case "DECISION":
                        return "LLM 回复生成完成";
                    default:
                        return "";
                }
            }

            @Override
            public void onError(String agentName, Throwable error) {
                // 不作为思考事件发射
            }
        }

        CollectingObservation chatObs = new CollectingObservation();
        AgentContext ctx = AgentContext.builder(chatId, userId)
            .courseId(chat.getCourseId())
            .observation(chatObs)
            .build();

        // ── Pre-stream: Detect generation intent (no LLM needed) ──
        // 意图前置判定：在流式开始前完成资源/规划模式的意图识别，为 Prompt 注入提供依据。
        String chatMode = request.getMode() != null ? request.getMode() : "chat";
        boolean planMode = "plan".equals(chatMode);

        ConversationAgent.GenerationIntent genIntent;
        boolean hasGenIntent;
        if ("resource".equals(chatMode)) {
            genIntent = conversationAgent.detectGenerationIntent(request.getContent(), messages);
            hasGenIntent = genIntent != null && genIntent.wantsGeneration();
        } else if (!planMode) {
            // 聊天模式（默认）— 执行生成意图探测
            genIntent = conversationAgent.detectGenerationIntent(request.getContent(), messages);
            hasGenIntent = genIntent != null && genIntent.wantsGeneration();
        } else {
            // 规划模式：跳过资源生成意图探测
            genIntent = null;
            hasGenIntent = false;
        }

        // 提前解决澄清问题（不依赖充足度结果）
        final String preClarifying = hasGenIntent
            ? conversationAgent.generateClarifyingQuestion(genIntent, null, courseName) : null;

        // ── Build pre-events: CONTEXT + RETRIEVE (emitted before streaming) ──
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
        ToolCallback ragCallback = null;
        if (chat.getCourseId() != null && !chat.getCourseId().isBlank()) {
            ragCallback = new RagToolCallback(ragTool, chat.getCourseId(),
                List.of(() -> {
                    ctx.put("rag_triggered", "true");
                    toolEventBuffer.add(
                        toSseEvent("agent.thought", Map.of(
                            "agentName", "ConversationAgent",
                            "agentRole", "conversation",
                            "phase", "RETRIEVE",
                            "context", "检测到知识性问题，LLM 决定检索课程知识库获取准确资料",
                            "observation", "正在检索课程知识库...",
                            "thought", "LLM 自主调用 rag_retrieve 工具",
                            "decision", "",
                            "confidenceLevel", "high",
                            "timestamp", Instant.now().toString()
                        ))
                    );
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
                            "phase", "RAG",
                            "context", "知识库检索完成，获取 " + count + " 条相关资料",
                            "observation", "检索到 " + count + " 条相关资料，LLM 将基于这些资料生成回答",
                            "thought", "RAG 检索完成并纳入回答上下文",
                            "decision", "",
                            "confidenceLevel", count >= 3 ? "high" : "medium",
                            "timestamp", Instant.now().toString()
                        )));
                    } catch (Exception e) {
                        log.warn("Failed to parse RAG result for SSE event: {}", e.getMessage());
                    }
                });
        }

        ctx.put("rag_tool", ragCallback);

        // ── Phase 1: Planner — intent analysis + reply planning ──
        // Planner uses ReAct (can call rag_retrieve), output streamed to thinking chain.
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

        // ── Phase 2: Generator — final visible reply ──
        // Generator has no tools, produces the text shown to the student.
        // Runs after planner completes, using the extracted plan.
        Flux<SseEvent> genPhase = Flux.defer(() -> {
            String fullPlannerText = plannerAccum.toString();
            log.info("Planner output received: {} chars", fullPlannerText.length());

            // Parse markers from planner output
            String analysisText = extractAnalysis(fullPlannerText);
            String planContent = extractPlan(fullPlannerText);
            log.info("Extracted — analysis: {} chars, plan: {} chars",
                analysisText != null ? analysisText.length() : 0,
                planContent != null ? planContent.length() : 0);

            // Store full planner output for DB persistence (used in postStream)
            ctx.put("planner_raw", fullPlannerText);

            // Emit a single PLANNING thought event with complete planner output
            // (combines both ANALYSIS and PLAN sections, consistent with thinking trace)
            List<SseEvent> preGenEvents = new ArrayList<>();
            String planningThought = planContent != null ? planContent : analysisText != null ? analysisText : fullPlannerText;
            if (planningThought != null && !planningThought.isEmpty()) {
                preGenEvents.add(toSseEvent("agent.thought", Map.of(
                    "agentName", "ConversationPlanner",
                    "agentRole", "conversation",
                    "phase", "PLANNING",
                    "context", "意图分析与回复规划完成",
                    "observation", "",
                    "thought", planningThought,
                    "decision", "",
                    "confidenceLevel", "high",
                    "timestamp", Instant.now().toString()
                )));
            }

            // Build generator input
            String lastUserMsg = messages.get(messages.size() - 1).get("content");
            ReplyGenerator.GenInput genInput = new ReplyGenerator.GenInput(
                buildCourseContext(userId, session.getCourseId()),
                buildProfileContext(userId, session.getCourseId()),
                planContent != null ? planContent : fullPlannerText,
                lastUserMsg);

            // Generator stream — pure text, no tools
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

        // ── Post-stream: evaluate sufficiency, handle genIntent, save, emit done ──
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

            // 处理生成意图（前置探测）+ 充足度结果
            boolean generationReady = false;
            Map<String, Object> generationMeta = null;
            StringBuilder extraText = new StringBuilder();

            if (hasGenIntent) {
                log.info("用户需要资源生成: chatId={}, prefs={}", chatId, genIntent.preferences());
                // 无论是否经过澄清路径，均解析主题
                String topic = conversationAgent.resolveTopic(
                    courseName != null ? courseName : "当前课程", messages, null);
                java.util.Map<String, Object> prefsWithTopic =
                    new java.util.LinkedHashMap<>(genIntent.preferences());
                prefsWithTopic.put("topic", topic);
                if (preClarifying != null) {
                    extraText.append("\n\n").append(preClarifying);
                    generationReady = true;
                    generationMeta = Map.of("stage", "clarifying", "preferences", prefsWithTopic);
                    log.info(">>> GEN READY: stage=clarifying, prefs={}", prefsWithTopic);
                } else {
                    generationReady = true;
                    generationMeta = Map.of("stage", "ready", "preferences", prefsWithTopic);
                    log.info(">>> GEN READY: stage=ready, prefs={}", prefsWithTopic);
                }
            }

            // 若未触发生成意图但充足度刚达标，提供资源生成选项
            if (sufficiency.sufficient() && !hasGenIntent && !planMode) {
                String offer = conversationAgent.generateResourceOffer(sufficiency);
                extraText.append(offer);
                generationReady = true;
                generationMeta = Map.of("stage", "offered",
                    "coveredCount", sufficiency.coveredCount(),
                    "confidence", sufficiency.overallConfidence());
            }

            // 规划模式：当充足度达标时提供规划生成选项
            boolean planGenerationReady = false;
            Map<String, Object> planGenerationMeta = null;
            if (planMode && sufficiency.sufficient()) {
                String offer = "\n\n---\n\n🎯 你的学习画像已就绪！需要我为你生成一份个性化的学习路径规划吗？\n\n"
                    + "我会根据你的目标和基础，规划完整的学习路线、推荐资源和节奏安排。";
                extraText.append(offer);
                planGenerationReady = true;
                planGenerationMeta = Map.of("stage", "offered",
                    "coveredCount", sufficiency.coveredCount());
            }

            // 保存助手消息到数据库
            String aiAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            Map<String, String> asstMsg = new LinkedHashMap<>();
            asstMsg.put("role", "assistant");
            asstMsg.put("content", fullReply);
            asstMsg.put("at", aiAt);
            // Store planner output (analysis + plan) alongside the clean reply
            String plannerOutput = ctx.get("planner_raw");
            if (plannerOutput != null && !plannerOutput.isEmpty()) {
                asstMsg.put("planning", plannerOutput);
            }
            messages.add(asstMsg);
            int asstMsgIdx = messages.size() - 1;
            session.setMessagesJson(toJson(messages));
            profileChatMapper.updateById(session);

            // 构建后置流项目：缓冲的观察事件 + REFLECT 事件 + 额外文本 + 完成信号
            java.util.List<SseEvent> postItems = new java.util.ArrayList<>(postThoughtEvents);

            // REFLECT event with sufficiency result (aligned with CONTEXT baseline)
            // 画像反射事件：向客户端同步最终的维度覆盖情况，作为本轮对话的阶段性总结。
            int effectiveCovered = Math.max(profileCovered, sufficiency.coveredCount());
            int effectiveMissing = Math.max(0, 7 - effectiveCovered);
            String reflectMissing = effectiveMissing > 0 ? "，还缺" + effectiveMissing + "个维度" : "";
            String reflectObs = "已覆盖 " + effectiveCovered + "/7 维度" + reflectMissing;
            String reflectDecision = sufficiency.sufficient()
                ? "SUFFICIENT — 画像充足"
                : "CONTINUE — 继续收集画像信息";
            postItems.add(toSseEvent("agent.thought", Map.of(
                "agentName", "ConversationAgent",
                "agentRole", "conversation",
                "phase", "REFLECT",
                "context", "画像覆盖度评估，已覆盖 " + effectiveCovered + "/7 维度",
                "observation", reflectObs,
                "thought", "覆盖度 " + effectiveCovered
                    + "/7，置信度 " + String.format("%.0f%%", sufficiency.overallConfidence() * 100),
                "decision", reflectDecision,
                "confidenceLevel", effectiveCovered >= 4 ? "high" : "medium",
                "timestamp", Instant.now().toString()
            )));

            if (extraText.length() > 0) {
                postItems.add(SseEvent.chunk(extraText.toString()));
            }
            Map<String, Object> doneData = new java.util.LinkedHashMap<>();
            doneData.put("profileReady", effectiveCovered >= 4);
            doneData.put("profileVersionId", "");
            doneData.put("coveredCount", effectiveCovered);
            doneData.put("generationReady", generationReady);
            doneData.put("generationMeta", generationMeta != null ? generationMeta : Map.of());
            doneData.put("planGenerationReady", planGenerationReady);
            doneData.put("planGenerationMeta", planGenerationMeta != null ? planGenerationMeta : Map.of());
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

            CompletableFuture.runAsync(() -> {
                try {
                    if (persistenceService != null) {
                        persistenceService.recordThinkingTrace(
                            fChatId, "ConversationAgent", "conversation",
                            "CONVERSATION",
                            "对话第" + (messages.size() / 2) + "轮，评估信息覆盖度",
                            "已覆盖 " + fSufficiency.coveredCount() + "/7 维度，置信度 " +
                                String.format("%.0f%%", fSufficiency.overallConfidence() * 100),
                            fSufficiency.sufficient()
                                ? "SUFFICIENT — 画像充足" : "CONTINUE — 继续收集画像信息",
                            fSufficiency.coveredCount() >= 4 ? "medium" : "low");
                    }

                    int asyncEffective = Math.max(profileCovered, fSufficiency.coveredCount());
                    String missingInfo = asyncEffective < 7
                        ? "，还缺" + (7 - asyncEffective) + "个维度"
                        : "";

                    java.util.List<Map<String, String>> steps = new java.util.ArrayList<>();
                    steps.add(Map.of(
                        "label", "理解上下文",
                        "icon", "📋",
                        "done", "true",
                        "detail", fContextObs
                    ));

                    // Add RETRIEVE/RAG steps if tools were called during planning
                    String ragTriggered = ctx.get("rag_triggered");
                    if ("true".equals(ragTriggered)) {
                        steps.add(Map.of(
                            "label", "检索知识库",
                            "icon", "🔗",
                            "done", "true",
                            "detail", "LLM 自主调用 rag_retrieve 工具检索课程知识库"
                        ));
                        String ragCount = ctx.get("rag_source_count");
                        int count = ragCount != null ? Integer.parseInt(ragCount) : 0;
                        steps.add(Map.of(
                            "label", "检索分析",
                            "icon", "🔍",
                            "done", "true",
                            "detail", "检索到 " + count + " 条相关资料，已纳入回答上下文"
                        ));
                    }

                    // Add combined planning step (ANALYSIS + PLAN seen during SSE live stream)
                    String fPlanning = messages.get(fAsstMsgIdx).get("planning");
                    if (fPlanning != null && !fPlanning.isEmpty()) {
                        steps.add(Map.of(
                            "label", "意图分析与回复规划",
                            "icon", "🔍",
                            "done", "true",
                            "detail", fPlanning
                        ));
                    }

                    steps.add(Map.of(
                        "label", "评估画像覆盖度",
                        "icon", "🎯",
                        "done", "true",
                        "detail", "已覆盖 " + asyncEffective + "/7 维度" + missingInfo
                    ));
                    String thinkingJson = objectMapper.writeValueAsString(Map.of(
                        "steps", steps,
                        "expanded", false
                    ));
                    messages.get(fAsstMsgIdx).put("thinking", thinkingJson);
                    session.setMessagesJson(toJson(messages));
                    profileChatMapper.updateById(session);

                    // 增量 delta 保存：每轮对话都做，无覆盖度阈值
                    // 增量更新策略：无论画像是否充足，均尝试从最新对话中提取微小变化，保持画像实时性。
                    if (fCourseId != null && !fCourseId.isEmpty()) {
                        profileService.updateProfileDelta(fUserId, fCourseId, fMessages, fChatId);
                    }
                } catch (Exception e) {
                    log.warn("异步画像更新失败: {}", e.getMessage());
                }
            }, profileAnalysisExecutor);

            return Flux.fromIterable(postItems);
        });

        return Flux.fromIterable(preEvents)
            .concatWith(plannerPhase)
            .concatWith(genPhase
                .doOnError(e -> log.error("Generation phase failed, continuing to post-stream: {}", e.getMessage()))
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
            if (pv == null || pv.getDimensionsJson() == null) return 0;
            List<Map<String, Object>> dimList = objectMapper.readValue(pv.getDimensionsJson(), new TypeReference<List<Map<String, Object>>>() {});
            if (dimList == null) return 0;
            int covered = 0;
            for (var dim : dimList) {
                @SuppressWarnings("unchecked")
                Map<String, Object> value = (Map<String, Object>) dim.get("value");
                if (value != null && !value.isEmpty()) covered++;
            }
            return covered;
        } catch (Exception e) { return 0; }
    }

    /** 从课程表与画像版本中提取课程上下文 */
    private String buildCourseContext(String userId, String courseId) {
        if (courseId == null) return "暂无课程信息（学生尚未选择课程）";

        String courseName = null;
        try {
            var course = courseMapper.selectById(courseId);
            if (course != null && course.getName() != null) {
                courseName = course.getName();
            }
        } catch (Exception e) {
            log.warn("查找课程名称失败: {}", e.getMessage());
        }

        String profileContext = "";
        if (userId != null) {
            try {
                Profile profile = profileMapper.selectOne(
                    new LambdaQueryWrapper<Profile>()
                        .eq(Profile::getUserId, userId)
                        .eq(Profile::getCourseId, courseId));
                if (profile != null && profile.getCurrentVersion() != null && profile.getCurrentVersion() > 0) {
                    ProfileVersion pv = profileVersionMapper.selectOne(
                        new LambdaQueryWrapper<ProfileVersion>()
                            .eq(ProfileVersion::getUserId, userId)
                            .eq(ProfileVersion::getCourseId, courseId)
                            .eq(ProfileVersion::getVersion, profile.getCurrentVersion()));
                    if (pv != null && pv.getDimensionsJson() != null) {
                        List<Map<String, Object>> dimList = objectMapper.readValue(pv.getDimensionsJson(),
                            new TypeReference<List<Map<String, Object>>>() {});
                        if (dimList != null) {
                            for (var dim : dimList) {
                                if ("major_context".equals(dim.get("key"))) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> value = (Map<String, Object>) dim.get("value");
                                    if (value != null) {
                                        String major = (String) value.getOrDefault("major", "");
                                        String chapter = (String) value.getOrDefault("current_chapter", "");
                                        if (!major.isEmpty()) profileContext += "，专业: " + major;
                                        if (!chapter.isEmpty()) profileContext += "，当前章节: " + chapter;
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("构建画像上下文失败: {}", e.getMessage());
            }
        }

        if (courseName != null) {
            return "课程: " + courseName + profileContext;
        }
        return "课程ID: " + courseId + profileContext + "（需通过对话了解学生的专业和课程）";
    }

    /** 构建已知画像维度的摘要 */
    private String buildProfileContext(String userId, String courseId) {
        if (userId == null || courseId == null) return "暂无画像数据，请从零开始了解学生";
        try {
            Profile profile = profileMapper.selectOne(
                new LambdaQueryWrapper<Profile>()
                    .eq(Profile::getUserId, userId)
                    .eq(Profile::getCourseId, courseId));
            if (profile == null || profile.getCurrentVersion() == null || profile.getCurrentVersion() == 0) {
                return "尚无画像数据。这是首次对话，请从基础信息开始了解学生。";
            }
            ProfileVersion pv = profileVersionMapper.selectOne(
                new LambdaQueryWrapper<ProfileVersion>()
                    .eq(ProfileVersion::getUserId, userId)
                    .eq(ProfileVersion::getCourseId, courseId)
                    .eq(ProfileVersion::getVersion, profile.getCurrentVersion()));
            if (pv == null || pv.getDimensionsJson() == null) return "画像数据暂不可用";

            List<Map<String, Object>> dimList = objectMapper.readValue(pv.getDimensionsJson(),
                new TypeReference<List<Map<String, Object>>>() {});
            if (dimList == null || dimList.isEmpty()) return "尚无画像数据。请从基础信息开始了解。";

            StringBuilder sb = new StringBuilder();
            int covered = 0;
            for (var dim : dimList) {
                String key = (String) dim.get("key");
                String label = (String) dim.getOrDefault("label", key);
                @SuppressWarnings("unchecked")
                Map<String, Object> value = (Map<String, Object>) dim.get("value");
                if (value != null && !value.isEmpty()) {
                    covered++;
                    sb.append("- ").append(label).append(": ").append(truncate(String.valueOf(value), 100)).append("\n");
                }
            }
            if (covered == 0) return "尚无有效画像数据。请从基础信息开始了解。";
            sb.insert(0, "已覆盖 " + covered + " 个维度：\n");
            if (covered < 7) sb.append("仍缺少 " + (7 - covered) + " 个维度的信息\n");
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
                return new ChatMessageDto(m.get("role"), m.get("content"), m.get("at"), thinking);
            })
            .toList();
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

    /** Extract text between [ANALYSIS] and [PLAN] markers from planner output. */
    private String extractAnalysis(String text) {
        if (text == null) return null;
        int analysisStart = text.indexOf("[ANALYSIS]");
        if (analysisStart < 0) return null;
        analysisStart += "[ANALYSIS]".length();
        // skip trailing newlines
        while (analysisStart < text.length() && (text.charAt(analysisStart) == '\n' || text.charAt(analysisStart) == '\r')) {
            analysisStart++;
        }
        int planStart = text.indexOf("[PLAN]", analysisStart);
        if (planStart < 0) return text.substring(analysisStart).trim();
        return text.substring(analysisStart, planStart).trim();
    }

    /** Extract text between [PLAN] and ---PLAN_END--- markers from planner output. */
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
}