package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.chat.*;
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
import com.learnthink.core.service.TaskPersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
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
        this.conversationAgent = conversationAgent;
        this.ragTool = ragTool;
    }

    @Override
    @Transactional
    public ChatStartResponse startChat(String userId, ChatStartRequest request) {
        // Resume logic (skip if forceNew):
        // 1) Prefer an active (unanalyzed) session that already has messages
        // 2) Fallback to the most recent non-empty session (even if analyzed)
        // This prevents returning a newer empty session that would make history look "missing".
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

        // Create empty session — no opening message, user initiates
        ProfileChat chat = new ProfileChat();
        chat.setUserId(userId);
        chat.setCourseId(request.getCourseId());
        chat.setMessagesJson("[]");
        profileChatMapper.insert(chat);

        return buildStartResponse(chat.getId(), request.getCourseId(), List.of());
    }

    @Override
    @Transactional
    public ChatSendResponse sendMessage(String userId, String chatId, ChatSendRequest request) {
        ProfileChat chat = profileChatMapper.selectById(chatId);
        if (chat == null || !chat.getUserId().equals(userId)) {
            throw new RuntimeException("Chat session not found");
        }

        List<Map<String, String>> messages = parseRawMessages(chat.getMessagesJson());

        // Append user message
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        messages.add(Map.of("role", "user", "content", request.getContent(), "at", now));

        // Call LLM with course context
        String aiResponse = callLLM(messages, userId, chat.getCourseId());
        String aiAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        messages.add(Map.of("role", "assistant", "content", aiResponse, "at", aiAt));

        // Save
        chat.setMessagesJson(toJson(messages));
        profileChatMapper.updateById(chat);

        // v3.1: Structured sufficiency evaluation via ConversationAgent
        boolean profileReady = false;
        String profileVersionId = null;
        boolean generationReady = false;
        Map<String, Object> generationMeta = null;

        ConversationAgent.SufficiencyResult sufficiency =
            conversationAgent.evaluateSufficiency(messages);
        profileReady = sufficiency.sufficient();

        // === Step A: Detect if user is responding to a generation offer ===
        ConversationAgent.GenerationIntent genIntent =
            conversationAgent.detectGenerationIntent(request.getContent());

        if (genIntent != null && genIntent.wantsGeneration()) {
            log.info("User wants resource generation: chatId={}, prefs={}", chatId, genIntent.preferences());

            // Check if requirements are clear enough
            String clarifying = conversationAgent.generateClarifyingQuestion(genIntent, sufficiency);
            if (clarifying != null) {
                // Need more info — append clarifying question to AI response
                aiResponse = aiResponse + "\n\n" + clarifying;
                generationReady = false; // not yet ready
                generationMeta = Map.of("stage", "clarifying", "preferences", genIntent.preferences());
            } else {
                // Requirements clear — trigger generation
                generationReady = true;
                generationMeta = Map.of("stage", "ready", "preferences", genIntent.preferences());

                // Async: trigger profile analysis + resource generation
                String finalChatId = chatId;
                String finalUserId = userId;
                CompletableFuture.runAsync(() -> {
                    try {
                        // 1. Profile analysis
                        ProfileSummaryDto summary = analyzeProfile(finalUserId, finalChatId);
                        ProfileChat pc = profileChatMapper.selectById(finalChatId);
                        if (pc != null) {
                            pc.setProfileVersionId(summary.getProfileVersionId());
                            profileChatMapper.updateById(pc);
                        }
                        log.info("Generation flow: profile ready, versionId={}", summary.getProfileVersionId());

                        // 2. Trigger resource generation via OrchestratorAgent
                        // (wired through TaskOrchestrator in production)
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
            generationReady = true; // frontend can show "生成资源" button
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

            // Record thinking trace
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

        List<ChatMessageDto> newMessages = List.of(
            new ChatMessageDto("assistant", aiResponse, aiAt, null));

        ChatSendResponse response = new ChatSendResponse(chatId, newMessages, profileReady, profileVersionId, generationReady, generationMeta);
        return response;
    }

    @Override
    public List<ChatMessageDto> getMessages(String userId, String chatId) {
        ProfileChat chat = profileChatMapper.selectById(chatId);
        if (chat == null || !chat.getUserId().equals(userId)) {
            throw new RuntimeException("Chat session not found");
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
            throw new RuntimeException("Chat session not found");
        }
        profileChatMapper.deleteById(chatId);
    }

    @Override
    @Transactional
    public ProfileSummaryDto analyzeProfile(String userId, String chatId) {
        ProfileChat chat = profileChatMapper.selectById(chatId);
        if (chat == null || !chat.getUserId().equals(userId)) {
            throw new RuntimeException("Chat session not found");
        }

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
        try {
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

            @SuppressWarnings("unchecked")
            Map<String, Object> summary = (Map<String, Object>) analysis.get("summary");

            // Resolve profile version
            Profile profile = profileMapper.selectOne(
                new LambdaQueryWrapper<Profile>()
                    .eq(Profile::getUserId, userId)
                    .eq(Profile::getCourseId, chat.getCourseId()));

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

            // Create profile version
            ProfileVersion pv = new ProfileVersion();
            pv.setUserId(userId);
            pv.setCourseId(chat.getCourseId());
            pv.setVersion(newVersion);
            pv.setDimensionsJson(objectMapper.writeValueAsString(dimensions));
            pv.setSummaryJson(objectMapper.writeValueAsString(summary));
            pv.setSourceChatIds(objectMapper.writeValueAsString(List.of(chatId)));
            profileVersionMapper.insert(pv);

            // Link chat to profile version
            chat.setProfileVersionId(pv.getId());
            profileChatMapper.updateById(chat);

            // Record profile analysis thinking trace (taskId = chatId for conversation traces)
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
            log.error("Failed to parse profile analysis result: {}", e.getMessage());
            throw new RuntimeException("Profile analysis failed: " + e.getMessage());
        }
    }

    @Override
    public Flux<String> streamMessage(String userId, String chatId, ChatSendRequest request) {
        ProfileChat chat = profileChatMapper.selectById(chatId);
        if (chat == null || !chat.getUserId().equals(userId)) {
            return Flux.error(new RuntimeException("Chat session not found"));
        }

        List<Map<String, String>> messages = parseRawMessages(chat.getMessagesJson());
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        messages.add(Map.of("role", "user", "content", request.getContent(), "at", now));

        // Save user message immediately
        chat.setMessagesJson(toJson(messages));
        profileChatMapper.updateById(chat);

        List<Message> chatMessages = buildChatMessages(messages, userId, chat.getCourseId());
        ChatClient client = chatClientBuilder.build();

        StringBuilder fullResponse = new StringBuilder();

        // v3.1: Prepend thinking chain event (PLAN phase) before streaming reply
        String planThought = toSseEvent("agent.thought", Map.of(
            "agentName", "ConversationAgent",
            "agentRole", "conversation",
            "phase", "PLAN",
            "context", "第" + (messages.size() / 2 + 1) + "轮对话，正在分析用户回复",
            "observation", "用户消息: " + (request.getContent().length() > 50 ?
                request.getContent().substring(0, 50) + "..." : request.getContent()),
            "thought", "分析用户回复，评估画像覆盖度变化...",
            "decision", "",
            "confidenceLevel", "medium",
            "timestamp", Instant.now().toString()
        ));

        return Flux.just(planThought)
            .concatWith(client.prompt().messages(chatMessages).stream().content()
            .doOnNext(fullResponse::append))
            .concatWith(Flux.defer(() -> {
                // After stream completes, save to DB and check profile
                String aiResponse = fullResponse.toString();
                String aiAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

                // Build assistant message with thinking data persisted (so history shows it)
                Map<String, String> asstMsg = new LinkedHashMap<>();
                asstMsg.put("role", "assistant");
                asstMsg.put("content", aiResponse);
                asstMsg.put("at", aiAt);
                // thinking will be set below after sufficiency evaluation
                messages.add(asstMsg);
                int asstMsgIdx = messages.size() - 1;
                chat.setMessagesJson(toJson(messages));
                profileChatMapper.updateById(chat);

                // v3.1: Structured sufficiency evaluation + generation offer
                ConversationAgent.SufficiencyResult sufficiency =
                    conversationAgent.evaluateSufficiency(messages);
                boolean profileReady = sufficiency.sufficient();
                String profileVersionId = null;
                boolean generationReady = false;

                // === Always emit REFLECT thinking chain event (v3.1 fix) ===
                java.util.List<String> thoughtEvents = new java.util.ArrayList<>();

                // Record thinking trace to MySQL (always)
                if (persistenceService != null) {
                    persistenceService.recordThinkingTrace(
                        chatId, "ConversationAgent", "conversation",
                        "CONVERSATION",
                        "对话第" + (messages.size() / 2) + "轮，评估信息覆盖度",
                        "已覆盖 " + sufficiency.coveredCount() + "/7 维度，置信度 " +
                            String.format("%.0f%%", sufficiency.overallConfidence() * 100),
                        sufficiency.sufficient() ?
                            "SUFFICIENT — 画像充足" : "CONTINUE — 继续收集画像信息",
                        sufficiency.coveredCount() >= 4 ? "medium" : "low");
                }

                // REFLECT event (always sent — not just when profileReady)
                String reflectThought = toSseEvent("agent.thought", Map.of(
                    "agentName", "ConversationAgent",
                    "agentRole", "conversation",
                    "phase", "REFLECT",
                    "context", "评估第" + (messages.size() / 2) + "轮对话后的画像覆盖度",
                    "observation", "已覆盖 " + sufficiency.coveredCount() + "/7 维度",
                    "thought", sufficiency.coveredCount() >= 6 ?
                        "画像已充分覆盖核心维度" :
                        "还缺" + (sufficiency.missingDimensions() != null ?
                            sufficiency.missingDimensions().size() : 0) + "个维度，继续收集信息",
                    "decision", sufficiency.sufficient() ?
                        "SUFFICIENT — 可生成资源" : "CONTINUE — 继续对话",
                    "confidenceLevel", sufficiency.coveredCount() >= 4 ? "medium" : "low",
                    "timestamp", Instant.now().toString()
                ));
                thoughtEvents.add(reflectThought);

                // Persist thinking data into the assistant message for history display
                try {
                    java.util.List<Map<String, String>> steps = new java.util.ArrayList<>();
                    steps.add(Map.of(
                        "label", "评估画像覆盖度",
                        "icon", "🎯",
                        "done", "true",
                        "detail", "已覆盖 " + sufficiency.coveredCount() + "/7 维度"
                    ));
                    String decisionLabel = sufficiency.sufficient() ? "SUFFICIENT — 可生成资源" : "CONTINUE — 继续对话";
                    String decisionDetail = sufficiency.coveredCount() >= 6 ?
                        "画像已充分覆盖核心维度" :
                        "还缺" + (sufficiency.missingDimensions() != null ? sufficiency.missingDimensions().size() : 0) + "个维度，继续收集信息";
                    steps.add(Map.of(
                        "label", decisionLabel,
                        "icon", "💡",
                        "done", "true",
                        "detail", decisionDetail
                    ));
                    String thinkingJson = objectMapper.writeValueAsString(Map.of(
                        "steps", steps,
                        "expanded", false
                    ));
                    messages.get(asstMsgIdx).put("thinking", thinkingJson);
                    chat.setMessagesJson(toJson(messages));
                    profileChatMapper.updateById(chat);
                } catch (Exception e) {
                    log.warn("Failed to persist thinking data: {}", e.getMessage());
                }

                if (profileReady) {
                    // Async profile analysis
                    String finalChatId2 = chatId;
                    String finalUserId2 = userId;
                    CompletableFuture.runAsync(() -> {
                        try {
                            ProfileSummaryDto summary = analyzeProfile(finalUserId2, finalChatId2);
                            ProfileChat pc = profileChatMapper.selectById(finalChatId2);
                            if (pc != null) {
                                pc.setProfileVersionId(summary.getProfileVersionId());
                                profileChatMapper.updateById(pc);
                            }
                        } catch (Exception e) {
                            log.error("Async profile analysis failed: {}", e.getMessage());
                        }
                    }, profileAnalysisExecutor);
                    generationReady = true;
                }

                String finalEvent = toSseEvent("done", Map.of(
                    "profileReady", profileReady,
                    "profileVersionId", profileVersionId != null ? profileVersionId : "",
                    "generationReady", generationReady
                ));

                java.util.List<String> allEvents = new java.util.ArrayList<>(thoughtEvents);
                allEvents.add(finalEvent);
                return Flux.fromIterable(allEvents);
            }));
    }

    private String callLLM(List<Map<String, String>> messages, String userId, String courseId) {
        ChatClient client = chatClientBuilder.build();
        List<Message> chatMessages = buildChatMessages(messages, userId, courseId);
        return client.prompt().messages(chatMessages).call().content();
    }

    private List<Message> buildChatMessages(List<Map<String, String>> messages) {
        return buildChatMessages(messages, null, null);
    }

    /** Extract course context from courses table + profile version */
    private String buildCourseContext(String userId, String courseId) {
        if (courseId == null) return "暂无课程信息（学生尚未选择课程）";

        // Step 1: Look up course name from courses table
        String courseName = null;
        try {
            var course = courseMapper.selectById(courseId);
            if (course != null && course.getName() != null) {
                courseName = course.getName();
            }
        } catch (Exception e) {
            log.warn("Failed to look up course name: {}", e.getMessage());
        }

        // Step 2: Look up profile for user-specific context
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
                        Map<String, Object> dims = objectMapper.readValue(pv.getDimensionsJson(),
                            new TypeReference<>() {});
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> dimList = (List<Map<String, Object>>) dims.get("dimensions");
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

            Map<String, Object> dims = objectMapper.readValue(pv.getDimensionsJson(),
                new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> dimList = (List<Map<String, Object>>) dims.get("dimensions");
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

    private List<Message> buildChatMessages(List<Map<String, String>> messages, String userId, String courseId) {
        List<Message> chatMessages = new ArrayList<>();

        // Build system prompt with dynamic course + profile context
        String template = promptLoader.get("chat/profile_chat");

        // Inject course context from profile
        String courseContext = buildCourseContext(userId, courseId);
        String profileContext = buildProfileContext(userId, courseId);

        String systemPrompt = template
            .replace("{course_context}", courseContext)
            .replace("{profile_context}", profileContext);

        // Append RAG knowledge context when user asks knowledge questions
        if (courseId != null) {
            String knowledgeContext = buildKnowledgeContext(messages, courseId);
            if (knowledgeContext != null) {
                systemPrompt = systemPrompt + knowledgeContext;
            }
        }
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

    private ProfileChat findActiveSession(String userId, String courseId) {
        LambdaQueryWrapper<ProfileChat> q = new LambdaQueryWrapper<>();
        q.eq(ProfileChat::getUserId, userId)
         .eq(ProfileChat::getCourseId, courseId)
         .isNull(ProfileChat::getProfileVersionId)
         .orderByDesc(ProfileChat::getCreatedAt)
         .last("LIMIT 1");
        return profileChatMapper.selectOne(q);
    }

    /**
     * Active session = unanalyzed session. This variant prefers sessions that already have messages,
     * so the UI can show history instead of a newer empty shell session.
     */
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

    /**
     * Most recent session that has at least one message (analyzed or not).
     */
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

    /** Serialize a thinking chain or event as an SSE event line */
    private String toSseEvent(String eventType, Map<String, Object> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            return "event:" + eventType + "\ndata:" + json + "\n\n";
        } catch (Exception e) {
            log.error("Failed to serialize SSE event: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Detect if the latest user message is a knowledge question and retrieve RAG context.
     * Returns augmented system prompt content, or null if no knowledge question detected.
     */
    private String buildKnowledgeContext(List<Map<String, String>> messages, String courseId) {
        if (ragTool == null || messages.isEmpty()) return null;

        String latestUserMsg = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) {
                latestUserMsg = messages.get(i).getOrDefault("content", "");
                break;
            }
        }

        // Rule-based detection: knowledge question indicators
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
}
