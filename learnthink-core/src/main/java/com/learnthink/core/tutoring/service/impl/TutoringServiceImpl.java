package com.learnthink.core.tutoring.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.learnthink.common.dto.tutoring.TutoringStartRequest;
import com.learnthink.core.domain.entity.TutoringAnswer;
import com.learnthink.core.domain.entity.TutoringSession;
import com.learnthink.core.tutoring.domain.*;
import com.learnthink.core.tutoring.event.TutoringEventEmitter;
import com.learnthink.core.tutoring.phase.*;
import com.learnthink.core.tutoring.repository.TutoringAnswerMapper;
import com.learnthink.core.tutoring.repository.TutoringSessionMapper;
import com.learnthink.core.tutoring.service.TutoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.LocalDateTime;
import java.util.*;
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
    private final TutoringSessionMapper sessionMapper;
    private final TutoringAnswerMapper answerMapper;
    private final ObjectMapper objectMapper;

    public TutoringServiceImpl(Phase1Architect phase1, Phase2Orchestrator phase2,
                                Phase3Generator phase3, SectionRegenerator sectionRegenerator,
                                ClarificationHandler clarificationHandler,
                                ReactStateStore reactStateStore,
                                TutoringSessionMapper sessionMapper,
                                TutoringAnswerMapper answerMapper,
                                ObjectMapper objectMapper) {
        this.phase1 = phase1;
        this.phase2 = phase2;
        this.phase3 = phase3;
        this.sectionRegenerator = sectionRegenerator;
        this.clarificationHandler = clarificationHandler;
        this.reactStateStore = reactStateStore;
        this.sessionMapper = sessionMapper;
        this.answerMapper = answerMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void startTutoring(TutoringStartRequest request, SseEmitter sseEmitter) {
        TutoringEventEmitter emitter = new TutoringEventEmitter(sseEmitter);

        try {
            // 入口参数校验：courseId 在 tutoring_sessions 表中为 NOT NULL，必须由调用方提供，
            // 否则会导致 ReAct 全部跑完后才在持久化阶段抛 SQL 异常，浪费 LLM 调用。
            if (request.courseId() == null || request.courseId().isBlank()) {
                log.warn("Tutoring start rejected: missing courseId, question={}", request.question());
                emitter.error("INVALID_PARAM", "courseId is required", "courseId", false);
                emitter.complete();
                return;
            }

            String userId = getUserId();
            TutoringContext.ClarificationResponse domainCr = toDomainClarificationResponse(
                request.clarificationResponse());
            TutoringContext context = new TutoringContext(
                userId, request.question(), request.sessionId(),
                null, null, null, null, null, domainCr);

            // Phase 1: ReAct → ExecutionPlan
            ExecutionPlan plan = phase1.execute(context, emitter);

            if (plan.isClarify()) {
                // Clarification needed - SSE stays open waiting for student
                String sessionForTimeout = plan.planId() != null ? plan.planId() : context.sessionId();
                ScheduledFuture<?> timeoutHandle = clarificationHandler.scheduleTimeout(
                    sessionForTimeout, emitter);
                log.info("Session {} waiting for clarification (timeout scheduled)", sessionForTimeout);
                // 该 handle 由前端跳过澄清后下一次调用清理（删除 react state 时一并失效）
                if (timeoutHandle == null) {
                    log.debug("Clarification timeout handle is null for {}", sessionForTimeout);
                }
                return;
            }

            // Phase 2: Resource retrieval（透传 courseId）
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

            // Persist session
            TutoringSession session = new TutoringSession();
            session.setId(plan.planId());
            session.setUserId(userId);
            session.setChatId(request.chatId());
            session.setCourseId(request.courseId());
            session.setQuestion(request.question());
            session.setExecutionPlan(toJson(plan));
            session.setStatus("generating");
            session.setCreatedAt(LocalDateTime.now());
            sessionMapper.insert(session);

            // Phase 3: Stream generation（阻塞直到 LLM 流结束）
            TutoringContext fullContext = new TutoringContext(
                userId, request.question(), plan.planId(),
                null, null, null, null, null, null);
            Map<String, String> sectionContents = phase3.generate(plan, resources, fullContext, emitter);

            // 持久化每个 section 内容
            if (sectionContents != null) {
                for (Map.Entry<String, String> e : sectionContents.entrySet()) {
                    TutoringAnswer answer = new TutoringAnswer();
                    answer.setId(UUID.randomUUID().toString());
                    answer.setSessionId(plan.planId());
                    answer.setSectionId(e.getKey());
                    answer.setContent(e.getValue());
                    answer.setCreatedAt(LocalDateTime.now());
                    answerMapper.insert(answer);
                }
            }

            // Update session status
            session.setStatus("completed");
            session.setCompletedAt(LocalDateTime.now());
            sessionMapper.updateById(session);

            emitter.done(plan.planId());
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

            // 加载原 section 内容作为再生 prompt 的上下文
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
    public List<?> getHistory(String sessionId) {
        TutoringSession session = sessionMapper.selectById(sessionId);
        if (session == null) return List.of();

        QueryWrapper<TutoringAnswer> wrapper = new QueryWrapper<>();
        wrapper.eq("session_id", sessionId).orderByAsc("created_at");
        List<TutoringAnswer> answers = answerMapper.selectList(wrapper);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getId());
        result.put("question", session.getQuestion());
        result.put("mode", "answer");
        ExecutionPlan plan = getPlan(sessionId);
        if (plan != null) {
            result.put("analysis", plan.questionAnalysis());
        }

        // 按 sectionBlueprints 顺序还原 sections
        List<Map<String, Object>> sections = new ArrayList<>();
        Map<String, String> contentBySection = new HashMap<>();
        for (TutoringAnswer a : answers) {
            contentBySection.put(a.getSectionId(), a.getContent());
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
                sections.add(s);
            }
        }
        result.put("sections", sections);
        return List.of(result);
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
            m.put("createdAt", s.getCreatedAt());
            m.put("messageCount", 0);
            return m;
        }).toList();
        return Map.of("records", records, "total", total != null ? total : 0L);
    }

    public void submitFeedback(String sessionId, String rating, String comment) {
        log.info("Feedback received for session {}: rating={}, comment={}",
            sessionId, rating, comment);
        // 反馈表暂未实现，仅记录日志；后续可加 tutoring_feedback 表
    }

    public void retryDiagram(String sessionId, String diagramId) {
        log.info("Retry diagram requested: session={}, diagram={}", sessionId, diagramId);
        // 图解重试需要重建 DiagramSpec；当前仅记录，后续配合 plan/diagram 元数据完成
    }

    private String loadOriginalAnswer(String sessionId, String sectionId) {
        try {
            QueryWrapper<TutoringAnswer> wrapper = new QueryWrapper<>();
            wrapper.eq("session_id", sessionId).orderByAsc("created_at");
            List<TutoringAnswer> all = answerMapper.selectList(wrapper);
            StringBuilder sb = new StringBuilder();
            for (TutoringAnswer a : all) {
                if (sectionId != null && sectionId.equals(a.getSectionId())) {
                    return a.getContent() != null ? a.getContent() : "";
                }
                sb.append("## ").append(a.getSectionId()).append("\n").append(a.getContent()).append("\n\n");
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
}
