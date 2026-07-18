package com.learnthink.core.tutoring.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.learnthink.common.dto.tutoring.GuidedAnswerRequest;
import com.learnthink.common.dto.tutoring.TutoringStartRequest;
import com.learnthink.core.domain.entity.ChatMessage;
import com.learnthink.core.domain.entity.ChatSession;
import com.learnthink.core.domain.entity.GuidedStepStateEntity;
import com.learnthink.core.domain.entity.TutoringSection;
import com.learnthink.core.domain.entity.TutoringSession;
import com.learnthink.core.repository.ChatMessageMapper;
import com.learnthink.core.repository.ChatSessionMapper;
import com.learnthink.core.service.chat.ChatMessageService;
import com.learnthink.core.tutoring.domain.*;
import com.learnthink.core.tutoring.event.TutoringEventEmitter;
import com.learnthink.core.tutoring.phase.*;
import com.learnthink.core.tutoring.kp.KpExtractorService;
import com.learnthink.core.tutoring.repository.TutoringSectionMapper;
import com.learnthink.core.tutoring.repository.TutoringSessionMapper;
import com.learnthink.core.tutoring.repository.GuidedStepStateMapper;
import com.learnthink.core.tutoring.service.TutoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

@Service
public class TutoringServiceImpl implements TutoringService {
    private static final Logger log = LoggerFactory.getLogger(TutoringServiceImpl.class);

    private final Phase1Architect phase1;
    private final Phase2Orchestrator phase2;
    private final Phase3Generator phase3;
    private final SectionRegenerator sectionRegenerator;
    private final ClarificationHandler clarificationHandler;
    private final ReactStateStore reactStateStore;
    private final GuidedDialogueLoop guidedDialogueLoop;
    private final TutoringSessionMapper sessionMapper;
    private final TutoringSectionMapper sectionMapper;
    private final GuidedStepStateMapper guidedStepStateMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatMessageService chatMessageService;
    private final KpExtractorService kpExtractorService;
    private final ObjectMapper objectMapper;

    public TutoringServiceImpl(Phase1Architect phase1, Phase2Orchestrator phase2,
                                Phase3Generator phase3, SectionRegenerator sectionRegenerator,
                                ClarificationHandler clarificationHandler,
                                ReactStateStore reactStateStore,
                                GuidedDialogueLoop guidedDialogueLoop,
                                TutoringSessionMapper sessionMapper,
                                TutoringSectionMapper sectionMapper,
                                GuidedStepStateMapper guidedStepStateMapper,
                                ChatSessionMapper chatSessionMapper,
                                ChatMessageMapper chatMessageMapper,
                                ChatMessageService chatMessageService,
                                KpExtractorService kpExtractorService,
                                ObjectMapper objectMapper) {
        this.phase1 = phase1;
        this.phase2 = phase2;
        this.phase3 = phase3;
        this.sectionRegenerator = sectionRegenerator;
        this.clarificationHandler = clarificationHandler;
        this.reactStateStore = reactStateStore;
        this.guidedDialogueLoop = guidedDialogueLoop;
        this.sessionMapper = sessionMapper;
        this.sectionMapper = sectionMapper;
        this.guidedStepStateMapper = guidedStepStateMapper;
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.chatMessageService = chatMessageService;
        this.kpExtractorService = kpExtractorService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void startTutoring(TutoringStartRequest request, SseEmitter sseEmitter) {
        TutoringEventEmitter emitter = new TutoringEventEmitter(sseEmitter);

        try {
            if (request.courseId() == null || request.courseId().isBlank()) {
                log.warn("Tutoring start rejected: missing courseId, question={}", request.question());
                emitter.error("INVALID_PARAM", "courseId is required", "courseId", false);
                emitter.complete();
                return;
            }

            String userId = getUserId();
            String subMode = request.mode() != null ? request.mode() : "smart";
            String chatId = request.chatId();

            // 提前生成 sessionId，确保 Phase1 的 tutoring.started、DB session、guided history 三者一致
            String sessionId = (request.sessionId() != null && !request.sessionId().isBlank())
                ? request.sessionId() : UUID.randomUUID().toString();
            TutoringContext.ClarificationResponse domainCr = toDomainClarificationResponse(
                request.clarificationResponse());

            TutoringContext context = new TutoringContext(
                userId, request.question(), sessionId,
                null, null, null, null, null, domainCr, subMode);

            // Phase 1: ReAct → ExecutionPlan
            ExecutionPlan plan = phase1.execute(context, emitter);

            if (plan.isClarify()) {
                // AI 追问 → 持久化到 chat_messages
                saveClarificationToChatMessages(chatId, userId, plan.clarification(),
                    plan.clarificationDecision(), sessionId, subMode);

                String sessionForTimeout = plan.planId() != null ? plan.planId() : context.sessionId();
                ScheduledFuture<?> timeoutHandle = clarificationHandler.scheduleTimeout(
                    sessionForTimeout, emitter);
                log.info("Session {} waiting for clarification (timeout scheduled)", sessionForTimeout);
                if (timeoutHandle == null) {
                    log.debug("Clarification timeout handle is null for {}", sessionForTimeout);
                }
                return;
            }

            // Phase 2: Resource retrieval
            List<ResourceRequirement> requirements = plan.resourceRequirements();
            ResolvedResources resources;
            if (requirements != null && !requirements.isEmpty()) {
                resources = phase2.execute(requirements, request.courseId());
                int unavailableCount = (int) resources.resources().values().stream()
                    .filter(List::isEmpty).count();
                List<String> unavailableIds = resources.resources().entrySet().stream()
                    .filter(e -> e.getValue().isEmpty())
                    .map(Map.Entry::getKey).toList();
                emitter.resourcesReady(
                    resources.resources().size() - unavailableCount,
                    unavailableCount, unavailableIds);
            } else {
                resources = new ResolvedResources(java.util.Map.of());
                emitter.resourcesReady(0, 0, List.of());
            }

            // Persist session — 复用 Phase1 的 sessionId，保持一致性
            TutoringSession session = new TutoringSession();
            session.setId(sessionId);
            session.setUserId(userId);
            if (chatId != null && chatSessionMapper.selectById(chatId) == null) {
                // 懒创建 chat session（与 ChatServiceImpl.streamMessage 行为一致）
                ChatSession chatSession = new ChatSession();
                chatSession.setId(chatId);
                chatSession.setUserId(userId);
                chatSession.setCourseId(request.courseId() != null ? request.courseId() : "");
                chatSession.setType("chat");
                chatSession.setStatus("active");
                chatSession.setMessageCount(0);
                chatSession.setCurrentRound(0);
                chatSession.setTitle(request.question() != null && request.question().length() > 0
                    ? (request.question().length() > 30 ? request.question().substring(0, 30) + "…" : request.question())
                    : "辅导会话");
                chatSession.setCreatedAt(LocalDateTime.now());
                chatSession.setUpdatedAt(LocalDateTime.now());
                try {
                    chatSessionMapper.insert(chatSession);
                    log.info("Lazy-created chat session for tutoring: chatId={}, userId={}", chatId, userId);
                } catch (Exception e) {
                    log.warn("Failed to lazy-create chat session {}: {}", chatId, e.getMessage());
                    chatId = null;
                }
            }
            session.setChatId(chatId);
            session.setCourseId(request.courseId());
            session.setQuestion(request.question());
            session.setExecutionPlan(toJson(plan));
            session.setStatus(plan.isGuided() ? "guiding" : "generating");
            session.setSubMode(request.mode() != null ? request.mode() : "smart");
            session.setCreatedAt(LocalDateTime.now());
            sessionMapper.insert(session);

            // 学生澄清响应 → 持久化到 chat_messages（session 创建后）
            if (domainCr != null) {
                saveClarificationResponseToChatMessages(chatId, userId, domainCr, sessionId, subMode);
            }

            // Guided 模式：进入引导对话循环
            if (plan.isGuided()) {
                // 持久化用户消息和 AI 占位消息到 chat_messages
                saveGuidedStartToChatMessages(chatId, userId, request.question(),
                    session.getId(), subMode, emitter.getReactThoughts());

                TutoringContext guidedContext = new TutoringContext(
                    userId, request.question(), session.getId(),
                    null, null, null, null, null, null, subMode);
                guidedDialogueLoop.execute(plan, resources, guidedContext, emitter);
                // 挂起等待学生作答时关闭 SSE；若全部完成则 loop 已自行 complete
                emitter.complete();
                return;
            }

            // Phase 3: Stream generation
            TutoringContext fullContext = new TutoringContext(
                userId, request.question(), session.getId(),
                null, null, null, null, null, null, subMode);
            Map<String, String> sectionContents = phase3.generate(plan, resources, fullContext, emitter);

            // 持久化每个 section 内容
            // 从 plan.sectionBlueprints 建立 sectionId → title 映射
            Map<String, String> sectionTitles = new HashMap<>();
            if (plan.sectionBlueprints() != null) {
                for (SectionBlueprint bp : plan.sectionBlueprints()) {
                    sectionTitles.put(bp.id(), bp.title());
                }
            }
            java.util.List<TutoringSection> savedSections = new ArrayList<>();
            if (sectionContents != null) {
                int order = 0;
                for (Map.Entry<String, String> e : sectionContents.entrySet()) {
                    TutoringSection section = new TutoringSection();
                    section.setId(UUID.randomUUID().toString());
                    section.setTutoringSessionId(session.getId());
                    section.setSectionId(e.getKey());
                    section.setTitle(sectionTitles.getOrDefault(e.getKey(), e.getKey()));
                    section.setSortOrder(order++);
                    section.setParentSectionId(null);
                    section.setContent(e.getValue());
                    section.setRating(null);
                    section.setFeedback(null);
                    section.setCreatedAt(LocalDateTime.now());
                    sectionMapper.insert(section);
                    savedSections.add(section);
                }
            }

            // Update session status
            session.setStatus("completed");
            session.setCompletedAt(LocalDateTime.now());
            sessionMapper.updateById(session);

            // 将用户问题和 AI 回复持久化到 chat 消息表
            saveToChatMessages(chatId, userId, request.question(), sectionContents,
                emitter.getReactThoughts(), session.getId(),
                request.mode() != null ? request.mode() : "smart");

            // 异步抽取知识点关联（非阻塞，失败不影响主流程）
            String courseId = request.courseId();
            if (!savedSections.isEmpty() && courseId != null) {
                CompletableFuture.runAsync(() -> {
                    try {
                        for (TutoringSection sec : savedSections) {
                            kpExtractorService.extractAndLink(sec, courseId);
                        }
                    } catch (Exception e) {
                        log.warn("KP extraction failed for session {}: {}", session.getId(), e.getMessage());
                    }
                });
            }

            emitter.done(session.getId());
            emitter.complete();

        } catch (Exception e) {
            log.error("Tutoring failed: {}", e.getMessage(), e);
            emitter.error("INTERNAL_ERROR", e.getMessage(), "*", false);
            emitter.complete();
        }
    }

    @Override
    public void regenerateSection(String sessionId, String sectionId, String action,
                                   String instruction, SseEmitter sseEmitter) {
        TutoringEventEmitter emitter = new TutoringEventEmitter(sseEmitter);
        try {
            ExecutionPlan plan = getPlan(sessionId);
            if (plan == null) {
                emitter.error("SESSION_NOT_FOUND", "Session not found: " + sessionId, "context", false);
                emitter.complete();
                return;
            }

            TutoringSession session = sessionMapper.selectById(sessionId);
            String courseId = session != null ? session.getCourseId() : null;
            ResolvedResources resources = phase2.execute(plan.resourceRequirements(), courseId);
            String originalAnswer = loadOriginalAnswer(sessionId, sectionId);
            sectionRegenerator.regenerate(sessionId, sectionId, action, instruction,
                plan, resources, originalAnswer, emitter);
            emitter.complete();
        } catch (Exception e) {
            log.error("Section regeneration failed: {}", e.getMessage(), e);
            emitter.error("REGENERATE_FAILED", e.getMessage(), "3", true);
            emitter.complete();
        }
    }

    @Override
    public void resumeGuidedDialogue(GuidedAnswerRequest request, SseEmitter sseEmitter) {
        TutoringEventEmitter emitter = new TutoringEventEmitter(sseEmitter);
        try {
            guidedDialogueLoop.resumeGuided(request, emitter);
            // 如果 loop 挂起（等待下一次作答），关闭 SSE；若全部完成则 loop 已自行 complete
            emitter.complete();
        } catch (Exception e) {
            log.error("Guided resume failed: {}", e.getMessage(), e);
            emitter.error("GUIDED_RESUME_FAILED", e.getMessage(), "guided", true);
            emitter.complete();
        }
    }

    @Override
    public ExecutionPlan getPlan(String sessionId) {
        TutoringSession session = sessionMapper.selectById(sessionId);
        if (session == null || session.getExecutionPlan() == null) return null;
        try {
            return objectMapper.readValue(session.getExecutionPlan(), ExecutionPlan.class);
        } catch (Exception e) {
            log.warn("Failed to parse execution plan for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    @Override
    public Map<String, Object> getHistory(String sessionId) {
        TutoringSession session = sessionMapper.selectById(sessionId);
        if (session == null) return Map.of();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getId());
        result.put("question", session.getQuestion());
        result.put("subMode", session.getSubMode() != null ? session.getSubMode() : "smart");
        ExecutionPlan plan = getPlan(sessionId);
        if (plan != null) {
            result.put("analysis", plan.questionAnalysis());
        }

        // guided 模式：返回 guidedSteps 状态
        if ("guided".equals(session.getSubMode())) {
            result.put("mode", "guided");
            result.put("sessionStatus", session.getStatus() != null ? session.getStatus() : "completed");
            List<GuidedStepStateEntity> stepEntities = guidedStepStateMapper.findAllBySession(sessionId);
            List<Map<String, Object>> guidedStepList = new ArrayList<>();
            for (GuidedStepStateEntity e : stepEntities) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", e.getStepId());
                s.put("order", e.getStepOrder());
                s.put("stage", e.getStage());
                s.put("title", e.getTitle());
                s.put("status", e.getStatus() != null ? e.getStatus() : "done");
                s.put("guidanceContent", e.getGuidanceContent() != null ? e.getGuidanceContent() : "");
                s.put("question", e.getQuestion() != null ? e.getQuestion() : "");
                s.put("studentAnswer", e.getStudentAnswer() != null ? e.getStudentAnswer() : "");
                s.put("feedback", e.getFeedback() != null ? e.getFeedback() : "");
                s.put("hint", e.getHint() != null ? e.getHint() : "");
                s.put("evaluation", e.getEvaluation() != null ? e.getEvaluation() : "");
                s.put("attempt", e.getAttemptCount() != null ? e.getAttemptCount() : 0);
                s.put("maxAttempts", e.getMaxAttempts() != null ? e.getMaxAttempts() : 2);
                s.put("allowReveal", false);
                s.put("timeSpentMs", e.getTimeSpentMs() != null ? e.getTimeSpentMs() : 0L);
                guidedStepList.add(s);
            }
            result.put("guidedSteps", guidedStepList);
            result.put("guidedSummary", session.getResultSummary() != null ? session.getResultSummary() : "");
            result.put("sections", List.of());
            return result;
        }

        // smart/direct 模式：原有逻辑
        result.put("mode", "answer");
        QueryWrapper<TutoringSection> wrapper = new QueryWrapper<>();
        wrapper.eq("tutoring_session_id", sessionId).orderByAsc("created_at");
        List<TutoringSection> sections = sectionMapper.selectList(wrapper);

        List<Map<String, Object>> sectionList = new ArrayList<>();
        Map<String, String> contentBySection = new HashMap<>();
        for (TutoringSection s : sections) {
            contentBySection.put(s.getSectionId(), s.getContent());
        }
        if (plan != null && plan.sectionBlueprints() != null) {
            for (SectionBlueprint bp : plan.sectionBlueprints()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", bp.id());
                s.put("title", bp.title());
                s.put("expandDefault", bp.expandDefault());
                s.put("status", "done");
                s.put("content", contentBySection.getOrDefault(bp.id(), ""));
                s.put("diagram", bp.expectedDiagram() != null
                    ? Map.of("status", "none", "diagramId", bp.expectedDiagram().id()) : null);
                s.put("regenerating", false);
                s.put("regeneratedContent", "");
                sectionList.add(s);
            }
        }
        result.put("sections", sectionList);
        return result;
    }

    public Map<String, Object> listSessions(int page, String courseId) {
        QueryWrapper<TutoringSession> wrapper = new QueryWrapper<>();
        String userId = getUserId();
        if (userId != null) wrapper.eq("user_id", userId);
        if (courseId != null && !courseId.isBlank()) wrapper.eq("course_id", courseId);
        wrapper.orderByDesc("created_at");
        int pageSize = 20;
        int offset = Math.max(0, (page - 1) * pageSize);
        wrapper.last("LIMIT " + pageSize + " OFFSET " + offset);
        List<TutoringSession> list = sessionMapper.selectList(wrapper);
        Long total = sessionMapper.selectCount(new QueryWrapper<TutoringSession>()
            .eq(userId != null, "user_id", userId)
            .eq(courseId != null && !courseId.isBlank(), "course_id", courseId));
        List<Map<String, Object>> records = list.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.getId());
            m.put("question", s.getQuestion());
            m.put("courseName", null);
            m.put("chapterLabel", null);
            m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null);
            m.put("messageCount", 0);
            return m;
        }).toList();
        return Map.of("records", records, "total", total != null ? total : 0L);
    }

    public void submitFeedback(String sessionId, String rating, String comment) {
        log.info("Feedback received for session {}: rating={}, comment={}",
            sessionId, rating, comment);
    }

    public void retryDiagram(String sessionId, String diagramId) {
        log.info("Retry diagram requested: session={}, diagram={}", sessionId, diagramId);
    }

    private String loadOriginalAnswer(String sessionId, String sectionId) {
        try {
            QueryWrapper<TutoringSection> wrapper = new QueryWrapper<>();
            wrapper.eq("tutoring_session_id", sessionId).orderByAsc("created_at");
            List<TutoringSection> all = sectionMapper.selectList(wrapper);
            StringBuilder sb = new StringBuilder();
            for (TutoringSection s : all) {
                if (sectionId != null && sectionId.equals(s.getSectionId())) {
                    return s.getContent() != null ? s.getContent() : "";
                }
                sb.append("## ").append(s.getSectionId()).append("\n").append(s.getContent()).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to load original answer for session {}: {}", sessionId, e.getMessage());
            return "";
        }
    }

    private String getUserId() {
        try {
            return com.learnthink.common.util.UserContextUtil.getCurrentUserId();
        } catch (Exception e) {
            return null;
        }
    }

    private TutoringContext.ClarificationResponse toDomainClarificationResponse(
            com.learnthink.common.dto.tutoring.ClarificationResponse dto) {
        if (dto == null) return null;
        return new TutoringContext.ClarificationResponse(dto.skipped(), dto.selectedOptionId(), dto.freeInput());
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * AI 追问时，将澄清内容写入 chat_messages（role=assistant）。
     */
    private void saveClarificationToChatMessages(String chatId, String userId,
            Clarification clarification, ClarificationDecision decision,
            String tutoringSessionId, String subMode) {
        if (chatId == null || chatId.isBlank() || clarification == null) return;
        try {
            ChatMessageService.MessageSeq asstSeq = chatMessageService.allocateSeq(chatId, "assistant");
            ChatMessage asstMsg = new ChatMessage();
            asstMsg.setSessionId(chatId);
            asstMsg.setUserId(userId);
            asstMsg.setSeqNum(asstSeq.seqNum());
            asstMsg.setRoundNum(asstSeq.roundNum());
            asstMsg.setRole("assistant");
            // 构造可读的澄清文本
            StringBuilder content = new StringBuilder();
            content.append("**").append(clarification.understoodPart()).append("**\n\n");
            if (clarification.options() != null) {
                for (int i = 0; i < clarification.options().size(); i++) {
                    var opt = clarification.options().get(i);
                    content.append(i + 1).append(". ").append(opt.label());
                    if (opt.detail() != null && !opt.detail().isBlank()) {
                        content.append(" — ").append(opt.detail());
                    }
                    content.append("\n");
                }
            }
            if (clarification.allowFreeInput()) {
                content.append("\n或输入你的具体问题...");
            }
            asstMsg.setContent(content.toString().trim());
            asstMsg.setMode("lecture");
            Map<String, Object> metaMap = new LinkedHashMap<>();
            metaMap.put("tutoring_session_id", tutoringSessionId);
            metaMap.put("tutoring_sub_mode", subMode);
            metaMap.put("type", "tutoring_clarification");
            metaMap.put("clarification", clarification);
            metaMap.put("clarificationDecision", decision);
            asstMsg.setMetadataJson(toJson(metaMap));
            chatMessageMapper.insert(asstMsg);
            log.info("Clarification message saved to chat_messages for chat {}", chatId);
        } catch (Exception e) {
            log.warn("Failed to save clarification to chat {}: {}", chatId, e.getMessage());
        }
    }

    /**
     * 学生澄清响应时，将选择写入 chat_messages（role=user）。
     */
    private void saveClarificationResponseToChatMessages(String chatId, String userId,
            TutoringContext.ClarificationResponse response,
            String tutoringSessionId, String subMode) {
        if (chatId == null || chatId.isBlank() || response == null) return;
        try {
            ChatMessageService.MessageSeq userSeq = chatMessageService.allocateSeq(chatId, "user");
            ChatMessage userMsg = new ChatMessage();
            userMsg.setSessionId(chatId);
            userMsg.setUserId(userId);
            userMsg.setSeqNum(userSeq.seqNum());
            userMsg.setRoundNum(userSeq.roundNum());
            userMsg.setRole("user");
            // 构造可读的用户响应文本
            String content;
            if (response.skipped()) {
                content = "（跳过澄清）";
            } else if (response.freeInput() != null && !response.freeInput().isBlank()) {
                content = response.freeInput();
            } else if (response.selectedOptionId() != null) {
                content = "选择了: " + response.selectedOptionId();
            } else {
                content = "（澄清响应）";
            }
            userMsg.setContent(content);
            userMsg.setMode("lecture");
            Map<String, Object> metaMap = new LinkedHashMap<>();
            metaMap.put("tutoring_session_id", tutoringSessionId);
            metaMap.put("tutoring_sub_mode", subMode);
            metaMap.put("type", "tutoring_clarification_response");
            metaMap.put("skipped", response.skipped());
            metaMap.put("selectedOptionId", response.selectedOptionId());
            metaMap.put("freeInput", response.freeInput());
            userMsg.setMetadataJson(toJson(metaMap));
            chatMessageMapper.insert(userMsg);
            log.info("Clarification response saved to chat_messages for chat {}", chatId);
        } catch (Exception e) {
            log.warn("Failed to save clarification response to chat {}: {}", chatId, e.getMessage());
        }
    }

    /**
     * 将辅导的用户问题和 AI 回复写入 chat_messages 表。
     */
    @SuppressWarnings("unchecked")
    private void saveToChatMessages(String chatId, String userId, String question,
                                     Map<String, String> sectionContents,
                                     java.util.List<java.util.Map<String, Object>> reactThoughts,
                                     String tutoringSessionId, String subMode) {
        if (chatId == null || chatId.isBlank()) return;
        try {
            // 写入 chat_messages
            ChatMessageService.MessageSeq userSeq = chatMessageService.allocateSeq(chatId, "user");
            ChatMessage userMsg = new ChatMessage();
            userMsg.setSessionId(chatId);
            userMsg.setUserId(userId);
            userMsg.setSeqNum(userSeq.seqNum());
            userMsg.setRoundNum(userSeq.roundNum());
            userMsg.setRole("user");
            userMsg.setContent(question);
            userMsg.setMode("lecture");
            userMsg.setMetadataJson(toJson(Map.of("tutoring_session_id", tutoringSessionId, "tutoring_sub_mode", subMode)));
            chatMessageMapper.insert(userMsg);

            // 拼接 AI 回复
            StringBuilder aiReply = new StringBuilder();
            if (sectionContents != null) {
                for (Map.Entry<String, String> e : sectionContents.entrySet()) {
                    aiReply.append(e.getValue()).append("\n\n");
                }
            }
            ChatMessageService.MessageSeq asstSeq = chatMessageService.allocateSeq(chatId, "assistant");
            ChatMessage asstMsg = new ChatMessage();
            asstMsg.setSessionId(chatId);
            asstMsg.setUserId(userId);
            asstMsg.setSeqNum(asstSeq.seqNum());
            asstMsg.setRoundNum(asstSeq.roundNum());
            asstMsg.setRole("assistant");
            asstMsg.setContent(aiReply.toString().trim());
            asstMsg.setMode("lecture");
            Map<String, Object> metaMap = new LinkedHashMap<>();
            metaMap.put("tutoring_session_id", tutoringSessionId);
            metaMap.put("tutoring_sub_mode", subMode);
            metaMap.put("tutoring_plan_mode", "answer");
            metaMap.put("section_ids", sectionContents != null ? sectionContents.keySet().stream().toList() : List.of());
            if (reactThoughts != null && !reactThoughts.isEmpty()) {
                metaMap.put("react_thoughts", reactThoughts);
            }
            asstMsg.setMetadataJson(toJson(metaMap));
            chatMessageMapper.insert(asstMsg);

            log.info("Tutoring messages written to chat_messages for chat {}", chatId);
        } catch (Exception e) {
            log.warn("Failed to save tutoring messages to chat {}: {}", chatId, e.getMessage());
        }
    }

    /**
     * Guided 模式开始时持久化用户消息（AI 消息占位，完成时更新）。
     */
    public void saveGuidedStartToChatMessages(String chatId, String userId, String question,
                                               String tutoringSessionId, String subMode,
                                               java.util.List<java.util.Map<String, Object>> reactThoughts) {
        if (chatId == null || chatId.isBlank()) return;
        try {
            ChatMessageService.MessageSeq userSeq = chatMessageService.allocateSeq(chatId, "user");
            ChatMessage userMsg = new ChatMessage();
            userMsg.setSessionId(chatId);
            userMsg.setUserId(userId);
            userMsg.setSeqNum(userSeq.seqNum());
            userMsg.setRoundNum(userSeq.roundNum());
            userMsg.setRole("user");
            userMsg.setContent(question);
            userMsg.setMode("lecture");
            Map<String, Object> userMeta = new LinkedHashMap<>();
            userMeta.put("tutoring_session_id", tutoringSessionId);
            userMeta.put("tutoring_sub_mode", subMode);
            if (reactThoughts != null && !reactThoughts.isEmpty()) {
                userMeta.put("react_thoughts", reactThoughts);
            }
            userMsg.setMetadataJson(toJson(userMeta));
            chatMessageMapper.insert(userMsg);

            // AI 消息占位（引导进行中）
            ChatMessageService.MessageSeq asstSeq = chatMessageService.allocateSeq(chatId, "assistant");
            ChatMessage asstMsg = new ChatMessage();
            asstMsg.setSessionId(chatId);
            asstMsg.setUserId(userId);
            asstMsg.setSeqNum(asstSeq.seqNum());
            asstMsg.setRoundNum(asstSeq.roundNum());
            asstMsg.setRole("assistant");
            asstMsg.setContent("(引导式教学进行中...)");
            asstMsg.setMode("lecture");
            Map<String, Object> metaMap = new LinkedHashMap<>();
            metaMap.put("tutoring_session_id", tutoringSessionId);
            metaMap.put("tutoring_sub_mode", subMode);
            metaMap.put("tutoring_plan_mode", "guided");
            if (reactThoughts != null && !reactThoughts.isEmpty()) {
                metaMap.put("react_thoughts", reactThoughts);
            }
            asstMsg.setMetadataJson(toJson(metaMap));
            chatMessageMapper.insert(asstMsg);

            log.info("Guided start messages written to chat_messages for chat {}", chatId);
        } catch (Exception e) {
            log.warn("Failed to save guided start messages to chat {}: {}", chatId, e.getMessage());
        }
    }

    /**
     * Guided 模式完成时更新 AI 消息内容。
     */
    public void updateGuidedCompletionToChatMessages(String chatId, String userId,
                                                      String tutoringSessionId, String guidedSummary) {
        if (chatId == null || chatId.isBlank()) return;
        try {
            // 找到该 chat 下最后一条 tutoring_session_id 匹配的 assistant 消息
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ChatMessage> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            wrapper.eq("session_id", chatId)
                   .eq("role", "assistant")
                   .like("metadata_json", tutoringSessionId)
                   .orderByDesc("seq_num")
                   .last("LIMIT 1");
            ChatMessage asstMsg = chatMessageMapper.selectOne(wrapper);
            if (asstMsg != null) {
                asstMsg.setContent(guidedSummary != null && !guidedSummary.isBlank()
                    ? guidedSummary : "(引导式教学已完成)");
                chatMessageMapper.updateById(asstMsg);
                log.info("Guided completion updated to chat_messages for chat {}", chatId);
            }
        } catch (Exception e) {
            log.warn("Failed to update guided completion to chat {}: {}", chatId, e.getMessage());
        }
    }
}
