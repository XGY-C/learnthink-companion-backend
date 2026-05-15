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
import com.learnthink.core.agent.impl.RetrieverAgent;
import com.learnthink.core.config.PromptLoader;
import com.learnthink.core.domain.entity.Profile;
import com.learnthink.core.domain.entity.ProfileChat;
import com.learnthink.core.domain.entity.ProfileVersion;
import com.learnthink.core.repository.CourseMapper;
import com.learnthink.core.repository.ProfileChatMapper;
import com.learnthink.core.repository.ProfileMapper;
import com.learnthink.core.repository.ProfileVersionMapper;
import com.learnthink.core.service.ChatService;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    private final ProfileChatMapper profileChatMapper;
    private final ProfileMapper profileMapper;
    private final ProfileVersionMapper profileVersionMapper;
    private final CourseMapper courseMapper;
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final PromptLoader promptLoader;
    private final TaskPersistenceService persistenceService;
    private final ProfileService profileService;
    private final ConversationAgent conversationAgent;
    private final RagTool ragTool;
    private final ExecutorService profileAnalysisExecutor = Executors.newFixedThreadPool(2);

    public ChatServiceImpl(ProfileChatMapper profileChatMapper,
                           ProfileMapper profileMapper,
                           ProfileVersionMapper profileVersionMapper,
                           CourseMapper courseMapper,
                           @Qualifier("chatChatClientBuilder") ChatClient.Builder chatClientBuilder,
                           ObjectMapper objectMapper,
                           PromptLoader promptLoader,
                           TaskPersistenceService persistenceService,
                           ProfileService profileService,
                           ConversationAgent conversationAgent,
                           RagTool ragTool) {
        this.profileChatMapper = profileChatMapper;
        this.profileMapper = profileMapper;
        this.profileVersionMapper = profileVersionMapper;
        this.courseMapper = courseMapper;
        this.chatClientBuilder = chatClientBuilder;
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
        this.persistenceService = persistenceService;
        this.profileService = profileService;
        this.conversationAgent = conversationAgent;
        this.ragTool = ragTool;
    }

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

        // Lazy creation: return a UUID without INSERT — DB record is created on first message
        String newId = java.util.UUID.randomUUID().toString();
        return buildStartResponse(newId, request.getCourseId(), List.of());
    }

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

        // Append user message
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        messages.add(Map.of("role", "user", "content", request.getContent(), "at", now));

        String knowledgeContext = chat.getCourseId() != null
            ? buildKnowledgeContext(messages, chat.getCourseId())
            : null;
        String systemPrompt = buildConversationSystemPrompt(userId, chat.getCourseId(), knowledgeContext);
        int roundNum = messages.size() / 2 + 1;
        AgentContext ctx = AgentContext.builder(chatId, userId)
            .courseId(chat.getCourseId())
            .build();
        var convResult = conversationAgent.execute(
            new ConversationAgent.ConversationInput(chat.getCourseId(), messages, roundNum, systemPrompt),
            ctx
        );

        String aiResponse = convResult.success()
            ? convResult.output().reply()
            : "抱歉，我现在无法生成回复，请稍后再试。";
        ConversationAgent.SufficiencyResult sufficiency = convResult.success()
            ? convResult.output().sufficiency()
            : new ConversationAgent.SufficiencyResult(false, 0, 0, List.of(), "");

        // v3.1: Structured sufficiency evaluation via ConversationAgent
        boolean profileReady = sufficiency.sufficient();
        String profileVersionId = null;
        boolean generationReady = false;
        Map<String, Object> generationMeta = null;

        // === Step A: Detect if user is responding to a generation offer ===
        ConversationAgent.GenerationIntent genIntent =
            conversationAgent.detectGenerationIntent(request.getContent());

        if (genIntent != null && genIntent.wantsGeneration()) {
            log.info("User wants resource generation: chatId={}, prefs={}", chatId, genIntent.preferences());

            String clarifying = conversationAgent.generateClarifyingQuestion(genIntent, sufficiency);
            if (clarifying != null) {
                aiResponse = aiResponse + "\n\n" + clarifying;
                generationReady = true;
                generationMeta = Map.of("stage", "clarifying", "preferences", genIntent.preferences());
            } else {
                generationReady = true;
                generationMeta = Map.of("stage", "ready", "preferences", genIntent.preferences());

                // Async: trigger profile analysis + resource generation
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
                        log.info("Generation flow: profile ready, versionId={}", summary.getProfileVersionId());

                        // Placeholder: trigger TaskOrchestrator.createTask()
                        log.info("Generation flow: would trigger TaskOrchestrator.createTask() for user={}", finalUserId);
                    } catch (Exception e) {
                        log.error("Generation flow failed: {}", e.getMessage());
                    }
                }, profileAnalysisExecutor);
            }
        }

        // === Step B: If sufficiency just reached, offer resource generation ===
        if (profileReady && genIntent == null) {
            String offer = conversationAgent.generateResourceOffer(sufficiency);
            aiResponse = aiResponse + offer;
            generationReady = true;
            generationMeta = Map.of("stage", "offered",
                "coveredCount", sufficiency.coveredCount(),
                "confidence", sufficiency.overallConfidence());

            // Async profile analysis (non-blocking)
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
                    log.error("Async profile analysis failed: {}", e.getMessage());
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
                    log.warn("Failed to persist thinking trace: {}", e.getMessage());
                }
            }
        }

        String aiAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        messages.add(Map.of("role", "assistant", "content", aiResponse, "at", aiAt));

        // Save
        chat.setMessagesJson(toJson(messages));
        profileChatMapper.updateById(chat);

        List<ChatMessageDto> newMessages = List.of(
            new ChatMessageDto("assistant", aiResponse, aiAt, null));

        return new ChatSendResponse(chatId, newMessages, profileReady, profileVersionId, generationReady, generationMeta);
    }

    @Override
    public List<ChatMessageDto> getMessages(String userId, String chatId) {
        ProfileChat chat = profileChatMapper.selectById(chatId);
        if (chat == null || !chat.getUserId().equals(userId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Chat session not found");
        }
        return parseMessages(chat.getMessagesJson());
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

            // Build conversation transcript for analysis
            StringBuilder transcript = new StringBuilder();
            for (var msg : messages) {
                String role = msg.get("role");
                if ("user".equals(role) || "assistant".equals(role) || "system".equals(role)) {
                    transcript.append(role).append(": ").append(msg.get("content")).append("\n");
                }
            }

            // Call LLM for profile extraction
            ChatClient client = chatClientBuilder.build();
            String response = client.prompt()
                .messages(
                    new SystemMessage(promptLoader.get("chat/profile_analysis")),
                    new UserMessage("对话记录：\n" + transcript)
                )
                .call()
                .content();

            // Parse the JSON response
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
                        chatId, "ProfileAgent", "profile",
                        "PROFILING",
                        "从 " + (messages != null ? messages.size() : 0) + " 轮对话中提取画像",
                        "已提取 " + dimCount + " 个维度，置信度 " + confMap,
                        "画像版本 " + newVersion + " 已生成",
                        dimCount >= 6 ? "high" : "medium");
                } catch (Exception e) {
                    log.warn("Failed to persist profile thinking trace: {}", e.getMessage());
                }
            }

            return new ProfileSummaryDto(pv.getId(), newVersion, summary,
                Map.of("dimensions", dimensions));

        } catch (Exception e) {
            log.error("Profile analysis failed for userId={}, chatId={}: {}", userId, chatId, e.getMessage());
            throw new RuntimeException("Profile analysis failed", e);
        } finally {
            lock.unlock();
        }
    }

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

        // ── Gather real context data before the LLM call ──
        String courseName = getCourseName(chat.getCourseId());
        int profileCovered = getProfileCoveredCount(userId, chat.getCourseId());
        log.info("上下文信息 - 课程: {}, 画像覆盖: {}/7", courseName != null ? courseName : "未知", profileCovered);

        String knowledgeContext = null;
        int ragHitCount = 0;
        if (chat.getCourseId() != null) {
            knowledgeContext = buildKnowledgeContext(messages, chat.getCourseId());
            if (knowledgeContext != null) {
                var m = java.util.regex.Pattern.compile("\\*\\*\\d+\\.")
                    .matcher(knowledgeContext);
                while (m.find()) ragHitCount++;
                log.info("RAG检索命中: {} 条资料", ragHitCount);
            }
        }

        String systemPrompt = buildConversationSystemPrompt(userId, chat.getCourseId(), knowledgeContext);
        final int finalRagHitCount = ragHitCount;

        // ── Build AgentContext with observation hook bridging to SSE ──
        class CollectingObservation implements AgentObservation {
            final List<SseEvent> sseEvents = new ArrayList<>();

            @Override
            public void onPrompt(String agentName, String prompt, Map<String, Object> params) {
                // Internal — not emitted as agent.thought events
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

                // Derive confidenceLevel from actual data, not hardcoded
                String confidenceLevel = deriveConfidence(phase);
                // Build context string with real data
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
                    case "RETRIEVE":
                        return "high";
                    case "RAG":
                        return finalRagHitCount >= 3 ? "high" : "medium";
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
                    case "RETRIEVE":
                        return "检索课程知识库，命中 " + finalRagHitCount + " 条资料";
                    case "RAG":
                        return "RAG 资料分析，共 " + finalRagHitCount + " 条来源";
                    case "DECISION":
                        return "LLM 回复生成完成";
                    default:
                        return "";
                }
            }

            @Override
            public void onError(String agentName, Throwable error) {
                // Not emitted as thought event
            }
        }

        CollectingObservation chatObs = new CollectingObservation();
        AgentContext ctx = AgentContext.builder(chatId, userId)
            .courseId(chat.getCourseId())
            .observation(chatObs)
            .build();

        // ── Pre-stream: Detect generation intent (no LLM needed) ──
        ConversationAgent.GenerationIntent genIntent =
            conversationAgent.detectGenerationIntent(request.getContent());

        // ── Build pre-events: CONTEXT + RETRIEVE (emitted before streaming) ──
        String contextObs = (courseName != null ? "课程: " + courseName : "课程已选择")
            + (profileCovered > 0 ? "，画像已覆盖 " + profileCovered + "/7 维度" : "，画像尚未建立");
        String contextThought = profileCovered >= 4
            ? "已充分了解学生背景，结合画像深入理解问题"
            : "画像信息有限，从对话中尽力理解学生需求";

        chatObs.onDecision("ConversationAgent",
            "CONTEXT — 第" + roundNum + "轮对话，"
                + (profileCovered >= 4 ? "画像充足（确信度高）" : "画像信息有限（继续收集）"),
            contextObs);

        if (knowledgeContext != null && ragHitCount > 0) {
            chatObs.onDecision("ConversationAgent",
                "RETRIEVE — 检索到 " + ragHitCount + " 条相关资料",
                "检测到知识问题，检索课程知识库。检索到 " + ragHitCount + " 条相关资料，将知识库资料作为回答参考依据");

            // Extract source titles from knowledge context for richer RAG thought
            java.util.List<String> sourceTitles = new java.util.ArrayList<>();
            java.util.regex.Matcher titleMatcher =
                java.util.regex.Pattern.compile("\\*\\*\\d+\\.\\s*([^\\*]+)\\*\\*")
                    .matcher(knowledgeContext);
            while (titleMatcher.find()) {
                sourceTitles.add(titleMatcher.group(1).trim());
            }
            String latestUserMsg = "";
            for (int i = messages.size() - 1; i >= 0; i--) {
                if ("user".equals(messages.get(i).get("role"))) {
                    latestUserMsg = messages.get(i).getOrDefault("content", "");
                    break;
                }
            }
            String sourcesSummary = sourceTitles.isEmpty()
                ? "共 " + ragHitCount + " 条资料"
                : String.join("、", sourceTitles.subList(0, Math.min(3, sourceTitles.size())))
                    + (sourceTitles.size() > 3 ? "等" + sourceTitles.size() + "篇" : "");

            chatObs.onDecision("ConversationAgent",
                "RAG — 采纳检索结果",
                "用户问题：「" + (latestUserMsg.length() > 30 ? latestUserMsg.substring(0, 30) + "…" : latestUserMsg) + "」→ "
                    + "检索到相关来源：" + sourcesSummary + "，采纳作为回答参考依据");
        }

        List<SseEvent> preEvents = new ArrayList<>(chatObs.sseEvents);

        // ── Buffer for collecting streamed LLM tokens ──
        StringBuilder replyBuffer = new StringBuilder();

        // ── True streaming: call LLM via Agent framework with ctx ──
        ConversationAgent.ConversationInput streamInput =
            new ConversationAgent.ConversationInput(chat.getCourseId(), messages, roundNum, systemPrompt);

        Flux<SseEvent> replyStream = conversationAgent.streamReply(streamInput, ctx)
            .doOnNext(replyBuffer::append)
            .map(SseEvent::chunk);

        // ── Post-stream: evaluate sufficiency, handle genIntent, save, emit done ──
        Flux<SseEvent> postStream = Flux.defer(() -> {
            String fullReply = replyBuffer.length() > 0
                ? replyBuffer.toString()
                : "抱歉，我现在无法生成回复，请稍后再试。";

            log.info("流式回复完成，长度: {} 字符", fullReply.length());

            // Collect post-stream observations (from onResponse's doFinally, which ran before this defer)
            List<SseEvent> postThoughtEvents = new ArrayList<>(chatObs.sseEvents);
            chatObs.sseEvents.clear();

            // Evaluate sufficiency (blocking LLM call, runs after stream completes)
            ConversationAgent.SufficiencyResult sufficiency;
            try {
                sufficiency = conversationAgent.evaluateSufficiency(messages);
            } catch (Exception e) {
                log.warn("Sufficiency evaluation failed, defaulting: {}", e.getMessage());
                sufficiency = new ConversationAgent.SufficiencyResult(false, 0, 0, List.of(), "");
            }

            log.info("画像充足度评估: sufficient={}, coveredCount={}/7, confidence={}",
                    sufficiency.sufficient(), sufficiency.coveredCount(),
                    String.format("%.0f%%", sufficiency.overallConfidence() * 100));

            // Process generation intent (detected pre-stream) + sufficiency
            boolean generationReady = false;
            Map<String, Object> generationMeta = null;
            StringBuilder extraText = new StringBuilder();

            if (genIntent != null && genIntent.wantsGeneration()) {
                log.info("用户需要资源生成: chatId={}, prefs={}", chatId, genIntent.preferences());
                String clarifying = conversationAgent.generateClarifyingQuestion(genIntent, sufficiency);
                if (clarifying != null) {
                    extraText.append("\n\n").append(clarifying);
                    generationReady = true;
                    generationMeta = Map.of("stage", "clarifying", "preferences", genIntent.preferences());
                } else {
                    generationReady = true;
                    generationMeta = Map.of("stage", "ready", "preferences", genIntent.preferences());
                }
            }

            // If sufficiency just reached without genIntent, offer resource generation
            if (sufficiency.sufficient() && genIntent == null) {
                String offer = conversationAgent.generateResourceOffer(sufficiency);
                extraText.append(offer);
                generationReady = true;
                generationMeta = Map.of("stage", "offered",
                    "coveredCount", sufficiency.coveredCount(),
                    "confidence", sufficiency.overallConfidence());
            }

            // Save assistant message to DB
            String aiAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            Map<String, String> asstMsg = new LinkedHashMap<>();
            asstMsg.put("role", "assistant");
            asstMsg.put("content", fullReply);
            asstMsg.put("at", aiAt);
            messages.add(asstMsg);
            int asstMsgIdx = messages.size() - 1;
            session.setMessagesJson(toJson(messages));
            profileChatMapper.updateById(session);

            // Build post-stream items: buffered observations + REFLECT event + extra text + done
            java.util.List<SseEvent> postItems = new java.util.ArrayList<>(postThoughtEvents);

            // REFLECT event with sufficiency result (aligned with CONTEXT baseline)
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
            postItems.add(toSseEvent("done", Map.of(
                "profileReady", false,
                "profileVersionId", "",
                "generationReady", generationReady,
                "generationMeta", generationMeta != null ? generationMeta : Map.of()
            )));

            // ── Async: thinking trace + incremental delta save ──
            final String fChatId = chatId;
            final String fUserId = userId;
            final int fAsstMsgIdx = asstMsgIdx;
            final List<Map<String, String>> fMessages = new ArrayList<>(messages);
            final ConversationAgent.SufficiencyResult fSufficiency = sufficiency;
            final String fContextObs = contextObs;
            final int fRagHitCount = finalRagHitCount;
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
                    if (fRagHitCount > 0) {
                        steps.add(Map.of(
                            "label", "检索知识库",
                            "icon", "🔗",
                            "done", "true",
                            "detail", "检索到 " + fRagHitCount + " 条相关资料"
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
                    if (fCourseId != null && !fCourseId.isEmpty()) {
                        profileService.updateProfileDelta(fUserId, fCourseId, fMessages, fChatId);
                    }
                } catch (Exception e) {
                    log.warn("Async profile update failed: {}", e.getMessage());
                }
            }, profileAnalysisExecutor);

            return Flux.fromIterable(postItems);
        });

        return Flux.fromIterable(preEvents)
            .concatWith(replyStream)
            .concatWith(postStream);
    }

    private List<Message> buildChatMessages(List<Map<String, String>> messages, String userId,
                                            String courseId, String knowledgeContext) {
        List<Message> chatMessages = new ArrayList<>();
        String systemPrompt = buildConversationSystemPrompt(userId, courseId, knowledgeContext);
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

    private List<Message> buildChatMessages(List<Map<String, String>> messages, String userId, String courseId) {
        String knowledgeContext = null;
        if (courseId != null) {
            knowledgeContext = buildKnowledgeContext(messages, courseId);
        }
        return buildChatMessages(messages, userId, courseId, knowledgeContext);
    }

    private String buildConversationSystemPrompt(String userId, String courseId, String knowledgeContext) {
        String template = promptLoader.get("chat/profile_chat");
        String courseContext = buildCourseContext(userId, courseId);
        String profileContext = buildProfileContext(userId, courseId);
        String systemPrompt = template
            .replace("{course_context}", courseContext)
            .replace("{profile_context}", profileContext);
        if (knowledgeContext != null) {
            systemPrompt = systemPrompt + knowledgeContext;
        }
        return systemPrompt;
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

    /** Extract course context from courses table + profile version */
    private String buildCourseContext(String userId, String courseId) {
        if (courseId == null) return "暂无课程信息（学生尚未选择课程）";

        String courseName = null;
        try {
            var course = courseMapper.selectById(courseId);
            if (course != null && course.getName() != null) {
                courseName = course.getName();
            }
        } catch (Exception e) {
            log.warn("Failed to look up course name: {}", e.getMessage());
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
                log.warn("Failed to build profile context: {}", e.getMessage());
            }
        }

        if (courseName != null) {
            return "课程: " + courseName + profileContext;
        }
        return "课程ID: " + courseId + profileContext + "（需通过对话了解学生的专业和课程）";
    }

    /** Build a summary of already-known profile dimensions */
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
            log.warn("Failed to build profile context: {}", e.getMessage());
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
                        // ignore parse failure
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
            log.warn("Failed to parse messages JSON: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Create a named SSE event with JSON data. */
    private SseEvent toSseEvent(String eventType, Map<String, Object> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            return SseEvent.named(eventType, json);
        } catch (Exception e) {
            log.error("Failed to serialize SSE event: {}", e.getMessage());
            return SseEvent.named(eventType, "{}");
        }
    }

    private String buildKnowledgeContext(List<Map<String, String>> messages, String courseId) {
        if (ragTool == null || messages.isEmpty()) return null;

        String latestUserMsg = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) {
                latestUserMsg = messages.get(i).getOrDefault("content", "");
                break;
            }
        }

        Set<String> knowledgeIndicators = Set.of(
            "什么是", "是什么", "怎么", "如何", "为什么", "解释", "定义",
            "区别", "对比", "原理", "概念", "公式", "定理", "算法",
            "代码", "实现", "例子", "示例", "举个"
        );
        boolean isKnowledgeQuestion = knowledgeIndicators.stream().anyMatch(latestUserMsg::contains);

        if (!isKnowledgeQuestion || courseId == null) return null;

        try {
            RetrieverAgent.RagClient.RagResponse resp =
                ragTool.retrieve(courseId, latestUserMsg, null, 3);
            if (resp == null || resp.sources() == null || resp.sources().isEmpty()) return null;

            StringBuilder ctx = new StringBuilder();
            ctx.append("\n\n## 知识库参考资料\n");
            ctx.append("以下是从课程知识库中检索到的相关资料，如相关可引用：\n\n");
            for (int i = 0; i < resp.sources().size(); i++) {
                var s = resp.sources().get(i);
                ctx.append("**").append(i + 1).append(". ").append(s.title()).append("**\n");
                ctx.append("> ").append(s.quote() != null ?
                    s.quote().substring(0, Math.min(200, s.quote().length())) : "").append("\n\n");
            }
            ctx.append("引用时标注来源文档标题。如果知识库资料与用户问题不直接相关，基于你自己的知识回答。\n");

            return ctx.toString();
        } catch (Exception e) {
            log.warn("RAG lookup failed for conversation: {}", e.getMessage());
            return null;
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("Failed to serialize to JSON: {}", e.getMessage());
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

    /** Count dimensions that contain at least one non-empty value field. */
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
}
