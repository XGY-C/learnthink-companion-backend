package com.learnthink.core.directanswer.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.directanswer.agent.AnswerPlannerAgent;
import com.learnthink.core.directanswer.agent.ProblemAnalyzerAgent;
import com.learnthink.core.directanswer.agent.SectionGeneratorAgent;
import com.learnthink.core.directanswer.domain.entity.DirectAnswerAnchor;
import com.learnthink.core.directanswer.domain.entity.DirectAnswerSection;
import com.learnthink.core.directanswer.domain.entity.DirectAnswerSession;
import com.learnthink.core.directanswer.domain.request.DirectAnswerStartRequest;
import com.learnthink.core.directanswer.domain.response.*;
import com.learnthink.core.directanswer.event.DirectAnswerEventEmitter;
import com.learnthink.core.directanswer.repository.DirectAnswerAnchorMapper;
import com.learnthink.core.directanswer.repository.DirectAnswerSectionMapper;
import com.learnthink.core.directanswer.repository.DirectAnswerSessionMapper;
import com.learnthink.core.tutoring.kp.KpExtractorService;
import com.learnthink.core.service.chat.ChatMessageService;
import com.learnthink.core.domain.entity.ChatMessage;
import com.learnthink.core.repository.ChatMessageMapper;
import com.learnthink.core.repository.ChatSessionMapper;
import com.learnthink.core.service.chat.ChatSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * DirectAnswer 服务实现。
 * 核心管线：Phase1(ProblemAnalyzer) → Phase2(AnswerPlanner) → Phase3(SectionGenerator)
 */
@Service
public class DirectAnswerServiceImpl implements DirectAnswerService {
    private static final Logger log = LoggerFactory.getLogger(DirectAnswerServiceImpl.class);

    private final ProblemAnalyzerAgent problemAnalyzer;
    private final AnswerPlannerAgent answerPlanner;
    private final SectionGeneratorAgent sectionGenerator;
    private final DirectAnswerSessionMapper sessionMapper;
    private final DirectAnswerSectionMapper sectionMapper;
    private final DirectAnswerAnchorMapper anchorMapper;
    private final KpExtractorService kpExtractor;
    private final ObjectMapper objectMapper;
    private final ChatMessageService chatMessageService;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatSessionService chatSessionService;

    public DirectAnswerServiceImpl(ProblemAnalyzerAgent problemAnalyzer,
                                   AnswerPlannerAgent answerPlanner,
                                   SectionGeneratorAgent sectionGenerator,
                                   DirectAnswerSessionMapper sessionMapper,
                                   DirectAnswerSectionMapper sectionMapper,
                                   DirectAnswerAnchorMapper anchorMapper,
                                   KpExtractorService kpExtractor,
                                   ObjectMapper objectMapper,
                                   ChatMessageService chatMessageService,
                                   ChatMessageMapper chatMessageMapper,
                                   ChatSessionMapper chatSessionMapper,
                                   ChatSessionService chatSessionService) {
        this.problemAnalyzer = problemAnalyzer;
        this.answerPlanner = answerPlanner;
        this.sectionGenerator = sectionGenerator;
        this.sessionMapper = sessionMapper;
        this.sectionMapper = sectionMapper;
        this.anchorMapper = anchorMapper;
        this.kpExtractor = kpExtractor;
        this.objectMapper = objectMapper;
        this.chatMessageService = chatMessageService;
        this.chatMessageMapper = chatMessageMapper;
        this.chatSessionMapper = chatSessionMapper;
        this.chatSessionService = chatSessionService;
    }

    @Override
    public String createSession(DirectAnswerStartRequest request) {
        String sessionId = UUID.randomUUID().toString();
        String userId = getUserId();
        String chatId = request.chatId();

        // 确保前端传来的 chatId 在 chat_sessions 表中存在（前端新建对话时可能尚未持久化），
        // 避免插入 direct_answer_sessions 时触发外键约束 fk_das_chat 失败。
        if (chatId != null && !chatId.isBlank() && chatSessionMapper.selectById(chatId) == null) {
            chatSessionService.lazyCreateSession(chatId, userId, request.courseId(), "lecture");
            log.info("Lazy-created chat session for DirectAnswer: chatId={}", chatId);
        }

        DirectAnswerSession session = new DirectAnswerSession();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setChatId(chatId);
        session.setCourseId(request.courseId());
        session.setQuestion(request.question());
        session.setStatus("creating");
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("mode", request.mode());
            meta.put("source", chatId != null ? "chat_planner" : "user_self_select");
            session.setMetadata(objectMapper.writeValueAsString(meta));
        } catch (Exception ignored) {}
        session.setCreatedAt(LocalDateTime.now());
        sessionMapper.insert(session);
        return sessionId;
    }

    @Override
    public void startAnswer(DirectAnswerStartRequest request, DirectAnswerEventEmitter emitter) {
        String sessionId = request.sessionId();
        String mode = request.mode() != null ? request.mode() : "direct_answer";
        String source = request.chatId() != null ? "chat_planner" : "user_self_select";
        String question = request.question();
        String courseId = request.courseId();

        try {
            // 1. 更新 session 状态
            DirectAnswerSession session = sessionMapper.selectById(sessionId);
            if (session != null) {
                session.setStatus("generating");
                sessionMapper.updateById(session);
            }

            // 2. 发送入口模式标识
            emitter.mode(mode, source);

            // 3. Phase1: 分析题型
            emitter.thought("正在分析题目类型...");
            AnalysisResult analysis = problemAnalyzer.analyze(question, courseId);
            emitter.sectionAnalysis(analysis);

            // 4. Phase2: 规划 7 段结构
            emitter.thought("正在规划讲解结构...");
            List<SectionBlueprint> blueprints = answerPlanner.plan(analysis, question);
            emitter.sectionPlan(blueprints);

            // 5. Phase3: 流式生成（双通道：思考 + 内容）
            SectionGeneratorAgent.GenerationResult genResult =
                sectionGenerator.generateStream(analysis, blueprints, question, emitter);

            // 6. 持久化各 section
            List<DirectAnswerSection> savedSections = new ArrayList<>();
            int order = 0;
            for (SectionBlueprint bp : blueprints) {
                DirectAnswerSection section = new DirectAnswerSection();
                section.setId(UUID.randomUUID().toString());
                section.setSessionId(sessionId);
                section.setSectionId(bp.id());
                section.setSectionOrder(order++);
                section.setTitle(bp.title());
                section.setContent(genResult.sectionContents().getOrDefault(bp.id(), ""));
                section.setStructured(toJson(genResult.sectionStructured().get(bp.id())));
                section.setCreatedAt(LocalDateTime.now());
                sectionMapper.insert(section);
                savedSections.add(section);
            }

            // 7. 持久化 anchor
            if (genResult.anchors() != null) {
                for (PrerequisiteAnchor a : genResult.anchors()) {
                    DirectAnswerAnchor anchor = new DirectAnswerAnchor();
                    anchor.setId(UUID.randomUUID().toString());
                    anchor.setSessionId(sessionId);
                    anchor.setStepId(a.stepId());
                    anchor.setKnowledgeCardId(a.knowledgeCardId());
                    anchor.setKnowledgeLabel(a.knowledgeLabel());
                    anchorMapper.insert(anchor);
                }
            }

            // 8. 更新 session 状态，并持久化 AnalysisResult 到 metadata 供历史回放
            if (session != null) {
                session.setStatus("completed");
                session.setCompletedAt(LocalDateTime.now());
                try {
                    Map<String, Object> meta = objectMapper.readValue(
                        session.getMetadata() != null ? session.getMetadata() : "{}",
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    meta.put("analysis", Map.of(
                        "problemType", analysis.problemType(),
                        "subject", analysis.subject(),
                        "tags", analysis.tags(),
                        "answerFirst", analysis.answerFirst()));
                    meta.put("thoughtContent", genResult.thoughtContent());
                    session.setMetadata(objectMapper.writeValueAsString(meta));
                } catch (Exception ignored) {}
                sessionMapper.updateById(session);
            }

            // 8.5 写入 chat_messages（索引 + 摘要），使聊天历史可恢复
            // 注意：startAnswer 在 CompletableFuture.runAsync 异步线程中执行，
            // ThreadLocal 中的用户信息不可用，需从 session 取 userId
            saveToChatMessages(request.chatId(),
                session != null ? session.getUserId() : getUserId(),
                question, genResult.sectionContents(), sessionId);

            // 9. 异步 KP 抽取
            if (!savedSections.isEmpty() && courseId != null) {
                CompletableFuture.runAsync(() -> {
                    try {
                        for (DirectAnswerSection sec : savedSections) {
                            kpExtractor.extractAndLinkForDirectAnswer(sec, courseId);
                        }
                    } catch (Exception e) {
                        log.warn("DirectAnswer KP extraction failed for {}: {}", sessionId, e.getMessage());
                    }
                });
            }

            emitter.done(sessionId, sessionId, blueprints.size(), mode);
            emitter.complete();

        } catch (Exception e) {
            log.error("DirectAnswer failed for session {}: {}", sessionId, e.getMessage(), e);
            emitter.error("INTERNAL_ERROR", e.getMessage(), true);
            emitter.complete();
        }
    }

    @Override
    public DirectAnswerResponse getAnswer(String sessionId) {
        DirectAnswerSession session = sessionMapper.selectById(sessionId);
        if (session == null) return null;

        List<DirectAnswerSection> sections = sectionMapper.selectList(
            new QueryWrapper<DirectAnswerSection>()
                .eq("session_id", sessionId)
                .orderByAsc("section_order"));

        // 构建 sections 列表 DTO，供前端历史回放恢复结构化渲染
        List<DirectAnswerSectionInfo> sectionInfos = sections.stream()
            .map(s -> new DirectAnswerSectionInfo(
                s.getSectionId(), s.getTitle(), s.getContent(), s.getSectionOrder()))
            .toList();

        // 从 metadata 重建 ProblemAnalysis，供前端 meta-bar 渲染（与发送时一致）
        ProblemAnalysis problemAnalysis = buildProblemAnalysis(session);
        AnswerMetadata metadata = buildMetadata(session, sections.size(), problemAnalysis);
        String thoughtContent = readThoughtContent(session);
        return new DirectAnswerResponse(sessionId, "direct_answer", "user_self_select",
            session.getQuestion(), null, problemAnalysis, null, null, null, null, null, metadata, sectionInfos, thoughtContent);
    }

    @Override
    public List<Map<String, Object>> listSessions(String userId, int page, int size) {
        int offset = Math.max(0, (page - 1) * size);
        List<DirectAnswerSession> sessions = sessionMapper.selectList(
            new QueryWrapper<DirectAnswerSession>()
                .eq("user_id", userId)
                .orderByDesc("started_at")
                .last("LIMIT " + size + " OFFSET " + offset));
        return sessions.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.getId());
            m.put("question", s.getQuestion());
            m.put("status", s.getStatus());
            m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null);
            m.put("completedAt", s.getCompletedAt() != null ? s.getCompletedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null);
            return m;
        }).toList();
    }

    /**
     * 从 session.metadata 重建 ProblemAnalysis，供历史回放 meta-bar 渲染。
     * conditionMappings/overallSummary/difficulty 留空——这些字段在发送时也不进入 meta-bar，
     * 且结构化内容由 problem_analysis section 自身承载。
     */
    @SuppressWarnings("unchecked")
    private ProblemAnalysis buildProblemAnalysis(DirectAnswerSession session) {
        try {
            Map<String, Object> meta = objectMapper.readValue(
                session.getMetadata() != null ? session.getMetadata() : "{}",
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            Object analysisObj = meta.get("analysis");
            if (analysisObj instanceof Map) {
                Map<String, Object> a = (Map<String, Object>) analysisObj;
                List<String> tags = a.get("tags") instanceof List
                    ? (List<String>) a.get("tags") : List.of();
                return new ProblemAnalysis(
                    (String) a.get("problemType"),
                    (String) a.get("subject"),
                    tags,
                    List.of(),
                    a.get("answerFirst") instanceof Boolean ? (Boolean) a.get("answerFirst") : false,
                    null,
                    null);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 从 session.metadata 读取持久化的 thoughtContent，供历史回放恢复思考过程。 */
    private String readThoughtContent(DirectAnswerSession session) {
        try {
            Map<String, Object> meta = objectMapper.readValue(
                session.getMetadata() != null ? session.getMetadata() : "{}",
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            Object thought = meta.get("thoughtContent");
            return thought instanceof String ? (String) thought : null;
        } catch (Exception ignored) {}
        return null;
    }

    private AnswerMetadata buildMetadata(DirectAnswerSession session, int sectionCount,
                                        ProblemAnalysis analysis) {
        return new AnswerMetadata(
            analysis != null ? analysis.problemType() : null,
            analysis != null ? analysis.subject() : null,
            analysis != null ? analysis.tags() : null,
            null,
            session.getCreatedAt() != null ? session.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null,
            session.getCompletedAt() != null ? session.getCompletedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null,
            sectionCount);
    }

    private String getUserId() {
        try {
            return com.learnthink.common.util.UserContextUtil.getCurrentUserId();
        } catch (Exception e) {
            return "anonymous";
        }
    }

    /**
     * 将直接解答的用户问题和 AI 回复写入 chat_messages 表（索引 + 摘要）。
     * 完整结构化数据仍在 direct_answer_sections 表，通过 direct_answer_session_id 关联。
     */
    private void saveToChatMessages(String chatId, String userId, String question,
                                     Map<String, String> sectionContents, String directAnswerSessionId) {
        if (chatId == null || chatId.isBlank()) return;
        try {
            // 用户消息
            ChatMessageService.MessageSeq userSeq = chatMessageService.allocateSeq(chatId, "user");
            ChatMessage userMsg = new ChatMessage();
            userMsg.setSessionId(chatId);
            userMsg.setUserId(userId);
            userMsg.setSeqNum(userSeq.seqNum());
            userMsg.setRoundNum(userSeq.roundNum());
            userMsg.setRole("user");
            userMsg.setContent(question);
            userMsg.setMode("lecture");
            userMsg.setMetadataJson(toJson(Map.of("direct_answer_session_id", directAnswerSessionId)));
            chatMessageMapper.insert(userMsg);

            // AI 回复（7段拼接纯文本 + 索引）
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
            metaMap.put("direct_answer_session_id", directAnswerSessionId);
            metaMap.put("direct_answer_mode", "direct");
            metaMap.put("section_ids", sectionContents != null
                ? sectionContents.keySet().stream().toList() : List.of());
            asstMsg.setMetadataJson(toJson(metaMap));
            chatMessageMapper.insert(asstMsg);

            log.info("DirectAnswer messages written to chat_messages for chat {}", chatId);
        } catch (Exception e) {
            log.warn("Failed to save DirectAnswer messages to chat {}: {}", chatId, e.getMessage());
        }
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); } catch (Exception e) { return "{}"; }
    }
}
