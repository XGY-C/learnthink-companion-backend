package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.practice.*;
import com.learnthink.common.exception.BusinessException;
import com.learnthink.common.exception.ErrorCode;
import com.learnthink.common.util.LlmJson;
import com.learnthink.core.agent.impl.EvidenceRetriever;
import com.learnthink.core.agent.impl.RagTool;
import com.learnthink.core.config.PromptLoader;
import com.learnthink.core.domain.entity.*;
import com.learnthink.core.repository.*;
import com.learnthink.core.service.PracticeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PracticeServiceImpl implements PracticeService {

    private final PracticeSessionMapper practiceSessionMapper;
    private final PracticeSessionItemMapper practiceSessionItemMapper;
    private final QuestionMapper questionMapper;
    private final QuestionAttemptMapper questionAttemptMapper;
    private final ProfileKpAnchorMapper profileKpAnchorMapper;
    private final ProfileVersionMapper profileVersionMapper;
    private final CourseKnowledgePointMapper courseKpMapper;
    private final ObjectMapper objectMapper;
    private final PromptLoader promptLoader;
    private final ChatClient genClient;
    private final ChatClient evalClient;
    private final RagTool ragTool;
    private final DailyLearningLogMapper dailyLearningLogMapper;

    private static final String PRACTICE_EVAL_SYSTEM_PROMPT = """
            你是高校学习辅导专家。请根据学生的本次练习会话数据，生成一份练习评估报告。

            输出 Markdown 格式，包含以下部分：
            ## 表现概述
            简要总结本次练习的整体表现。

            ## 掌握诊断
            分析各知识点的掌握情况，指出已掌握和仍需加强的知识点。

            ## 错题分析
            针对做错的题目，分析错误可能的原因（概念混淆、粗心、方法不当等）。

            ## 改进建议
            给出具体的后续学习建议，包括推荐复习的知识点和练习方向。

            【要求】
            - 评语具体、指向本次练习，避免空话套话
            - 不要复述题目原文，引用关键点即可
            - 只输出评估报告，不要寒暄与解释
            """;

    public PracticeServiceImpl(PracticeSessionMapper practiceSessionMapper,
                               PracticeSessionItemMapper practiceSessionItemMapper,
                               QuestionMapper questionMapper,
                               QuestionAttemptMapper questionAttemptMapper,
                               ProfileKpAnchorMapper profileKpAnchorMapper,
                               ProfileVersionMapper profileVersionMapper,
                               CourseKnowledgePointMapper courseKpMapper,
                               ObjectMapper objectMapper,
                               PromptLoader promptLoader,
                               @Qualifier("generationChatClientBuilder") ChatClient.Builder genClientBuilder,
                               @Qualifier("chatChatClientBuilder") ChatClient.Builder chatClientBuilder,
                               RagTool ragTool,
                               DailyLearningLogMapper dailyLearningLogMapper) {
        this.practiceSessionMapper = practiceSessionMapper;
        this.practiceSessionItemMapper = practiceSessionItemMapper;
        this.questionMapper = questionMapper;
        this.questionAttemptMapper = questionAttemptMapper;
        this.profileKpAnchorMapper = profileKpAnchorMapper;
        this.profileVersionMapper = profileVersionMapper;
        this.courseKpMapper = courseKpMapper;
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
        this.genClient = genClientBuilder.build();
        this.evalClient = chatClientBuilder.build();
        this.ragTool = ragTool;
        this.dailyLearningLogMapper = dailyLearningLogMapper;
    }

    // ==================== 创建会话 ====================

    @Override
    @Transactional
    public PracticeSessionDTO createSession(String userId, CreatePracticeSessionRequest req) {
        String courseId = req.getCourseId();
        String sessionType = req.getSessionType();
        int count = req.getQuestionCount() != null ? req.getQuestionCount() : 10;
        if (count <= 0) count = 10;
        if (count > 50) count = 50;

        List<Question> questions = new ArrayList<>();

        switch (sessionType) {
            case "custom" -> {
                if (req.getQuestionIds() == null || req.getQuestionIds().isEmpty()) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "手动挑题需提供 questionIds");
                }
                List<Question> picked = questionMapper.selectBatchIds(req.getQuestionIds());
                questions = picked.stream()
                        .filter(q -> q.getUserId().equals(userId) && q.getDeletedAt() == null)
                        .toList();
            }
            case "kp_focus" -> {
                questions = selectByTypeCounts(userId, courseId, req.getKpIds(),
                        req.getDifficulty(), req.getTypeCounts(), count);
            }
            case "random" -> {
                questions = selectByTypeCounts(userId, courseId, null,
                        req.getDifficulty(), req.getTypeCounts(), count);
            }
            case "wrong_review" -> {
                Map<String, Integer> typeCounts = req.getTypeCounts();
                int fetchLimit = (typeCounts != null && !typeCounts.isEmpty()) ? 50 : count;
                List<String> wrongIds = questionAttemptMapper.selectWrongQuestionIds(userId, courseId, fetchLimit);
                if (!wrongIds.isEmpty()) {
                    List<Question> wrongQuestions = questionMapper.selectBatchIds(wrongIds);
                    List<Question> filtered = wrongQuestions.stream()
                            .filter(q -> q.getDeletedAt() == null && q.getUserId().equals(userId))
                            .toList();
                    if (typeCounts != null && !typeCounts.isEmpty()) {
                        questions = pickByTypeCounts(filtered, typeCounts);
                    } else {
                        questions = filtered.stream().limit(count).toList();
                    }
                }
            }
            default -> throw new BusinessException(ErrorCode.PARAM_ERROR, "不支持的会话类型: " + sessionType);
        }

        if (questions.isEmpty()) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "没有符合条件的题目，请先在题库中添加题目");
        }

        PracticeSession session = new PracticeSession();
        session.setUserId(userId);
        session.setCourseId(courseId);
        session.setSessionType(sessionType);
        if (req.getKpIds() != null && !req.getKpIds().isEmpty()) {
            try {
                session.setKpFilterJson(objectMapper.writeValueAsString(req.getKpIds()));
            } catch (Exception ignored) {}
        }
        session.setDifficultyFilter(req.getDifficulty() != null ? String.valueOf(req.getDifficulty()) : null);
        session.setQuestionCount(questions.size());
        session.setCorrectCount(0);
        session.setTotalDurationSeconds(0);
        session.setCompleted(false);
        session.setAiGeneratedCount(0);
        practiceSessionMapper.insert(session);

        List<PracticeSessionItem> items = insertItems(session.getId(), questions);
        return toSessionDTOFromQuestions(session, questions, buildItemMap(items));
    }

    /** 按题型分别抽题；typeCounts 为空时走原有逻辑 */
    private List<Question> selectByTypeCounts(String userId, String courseId, List<String> kpIds,
                                              Integer difficulty, Map<String, Integer> typeCounts, int defaultCount) {
        if (typeCounts == null || typeCounts.isEmpty()) {
            return questionMapper.selectByKpIdsRandom(userId, courseId, kpIds, difficulty, null, defaultCount);
        }
        List<Question> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : typeCounts.entrySet()) {
            int n = entry.getValue() != null ? entry.getValue() : 0;
            if (n > 0) {
                result.addAll(questionMapper.selectByKpIdsRandom(
                        userId, courseId, kpIds, difficulty, List.of(entry.getKey()), n));
            }
        }
        return result;
    }

    /** 从已有题目列表中按题型分别取指定数量 */
    private List<Question> pickByTypeCounts(List<Question> questions, Map<String, Integer> typeCounts) {
        List<Question> result = new ArrayList<>();
        Map<String, List<Question>> byType = questions.stream()
                .collect(Collectors.groupingBy(Question::getQuestionType));
        for (Map.Entry<String, Integer> entry : typeCounts.entrySet()) {
            int n = entry.getValue() != null ? entry.getValue() : 0;
            if (n > 0) {
                List<Question> pool = byType.getOrDefault(entry.getKey(), List.of());
                result.addAll(pool.stream().limit(n).toList());
            }
        }
        return result;
    }

    // ==================== AI 智能组卷 ====================

    @Override
    @Transactional
    public PracticeSessionDTO aiGenerateSession(String userId, AiGenerateSessionRequest req) {
        String courseId = req.getCourseId();
        int count = req.getCount() != null ? req.getCount() : 10;
        if (count <= 0) count = 10;
        if (count > 50) count = 50;

        List<String> weakKpIds = determineWeakKps(userId, courseId, req.getFocus(), req.getKpIds());
        Integer difficulty = req.getDifficulty();
        List<String> types = req.getTypes();

        ProfileVersion latestPv = selectLatestProfileVersion(userId, courseId);
        String profileVersionId = latestPv != null ? latestPv.getId() : null;

        // 先从题库按条件抽取
        List<Question> bankQuestions = questionMapper.selectByKpIdsRandom(
                userId, courseId, weakKpIds, difficulty, types, count);

        int aiGenerated = 0;
        List<Question> allQuestions = new ArrayList<>(bankQuestions);

        // 确定每组需要生成的题型和数量
        List<String> targetTypes;
        if (types != null && !types.isEmpty()) {
            targetTypes = types;
        } else {
            targetTypes = List.of("single_choice", "multiple_choice", "true_false", "fill_blank", "essay");
        }

        // 统计已有题型数量，计算每种题型还需要生成几题
        Map<String, Long> existingCountByType = bankQuestions.stream()
                .collect(Collectors.groupingBy(Question::getQuestionType, Collectors.counting()));

        if (allQuestions.size() < count && !weakKpIds.isEmpty()) {
            int kpIdx = 0;
            while (allQuestions.size() < count && kpIdx < weakKpIds.size() * 5) {
                String kpId = weakKpIds.get(kpIdx % weakKpIds.size());
                // 轮转题型，优先生成缺口大的题型
                String targetType = targetTypes.get(kpIdx % targetTypes.size());
                long existing = existingCountByType.getOrDefault(targetType, 0L);
                long needed = (count / targetTypes.size()) + (kpIdx % targetTypes.size() < count % targetTypes.size() ? 1 : 0);
                if (existing >= needed) {
                    kpIdx++;
                    continue;
                }
                Question generated = generateQuestionByKp(userId, courseId, kpId, targetType, difficulty);
                if (generated != null) {
                    allQuestions.add(generated);
                    aiGenerated++;
                    existingCountByType.merge(targetType, 1L, Long::sum);
                }
                kpIdx++;
            }
        }

        if (allQuestions.isEmpty()) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                    "AI 组卷失败：题库无可用题目且 AI 生成失败，请稍后重试");
        }

        PracticeSession session = new PracticeSession();
        session.setUserId(userId);
        session.setCourseId(courseId);
        session.setSessionType("weak_point");
        session.setProfileVersionId(profileVersionId);
        try {
            session.setWeakKpsJson(objectMapper.writeValueAsString(weakKpIds));
        } catch (Exception ignored) {}
        session.setAiGeneratedCount(aiGenerated);
        session.setQuestionCount(allQuestions.size());
        session.setCorrectCount(0);
        session.setTotalDurationSeconds(0);
        session.setCompleted(false);
        practiceSessionMapper.insert(session);

        List<PracticeSessionItem> items = insertItems(session.getId(), allQuestions);
        return toSessionDTOFromQuestions(session, allQuestions, buildItemMap(items));
    }

    private List<String> determineWeakKps(String userId, String courseId, String focus, List<String> kpIds) {
        if ("kp_focus".equals(focus) && kpIds != null && !kpIds.isEmpty()) {
            return kpIds;
        }
        ProfileVersion latestPv = selectLatestProfileVersion(userId, courseId);
        if (latestPv == null) return List.of();

        List<ProfileKpAnchor> weakAnchors = profileKpAnchorMapper.selectList(
                new LambdaQueryWrapper<ProfileKpAnchor>()
                        .eq(ProfileKpAnchor::getProfileVersionId, latestPv.getId())
                        .eq(ProfileKpAnchor::getRelationType, "weak"));
        return weakAnchors.stream()
                .map(ProfileKpAnchor::getKpId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private ProfileVersion selectLatestProfileVersion(String userId, String courseId) {
        return profileVersionMapper.selectOne(
                new LambdaQueryWrapper<ProfileVersion>()
                        .eq(ProfileVersion::getUserId, userId)
                        .eq(ProfileVersion::getCourseId, courseId)
                        .orderByDesc(ProfileVersion::getVersion)
                        .last("LIMIT 1"));
    }

    private Question generateQuestionByKp(String userId, String courseId, String kpId,
                                          String questionType, Integer difficulty) {
        try {
            CourseKnowledgePoint kp = courseKpMapper.selectById(kpId);
            if (kp == null) return null;

            EvidenceRetriever.RagClient.RagResponse rag =
                    ragTool.retrieve(courseId, kp.getName(), kp.getName(), 3);
            String evidence = formatEvidence(rag);

            String diffStr = difficulty != null ? String.valueOf(difficulty) : "3";
            String template = promptLoader.get("practice/single_question_gen");
            String prompt = template
                    .replace("{kpName}", kp.getName())
                    .replace("{difficulty}", diffStr)
                    .replace("{questionType}", questionType)
                    .replace("{evidence}", evidence);

            String raw = genClient.prompt()
                    .messages(new SystemMessage(prompt),
                              new UserMessage("请生成一道「" + questionType + "」关于「" + kp.getName() + "」的题目。"))
                    .call().content();

            JsonNode node = LlmJson.readTree(raw);
            if (node == null) {
                log.warn("AI 题目生成 JSON 解析失败, kpId={}", kpId);
                return null;
            }

            Question q = new Question();
            q.setUserId(userId);
            q.setCourseId(courseId);
            q.setKpId(kpId);
            q.setQuestionType(node.has("question_type") ? node.get("question_type").asText() : questionType);
            q.setTitle(node.has("title") ? node.get("title").asText() : "");
            if (node.has("options") && !node.get("options").isNull()) {
                q.setOptionsJson(objectMapper.writeValueAsString(node.get("options")));
            }
            if (node.has("answer")) {
                q.setAnswerJson(objectMapper.writeValueAsString(node.get("answer")));
            }
            q.setExplanation(node.has("explanation") ? node.get("explanation").asText() : "");
            q.setDifficulty(node.has("difficulty") ? node.get("difficulty").asInt(3) : (difficulty != null ? difficulty : 3));
            q.setAttemptCount(0);
            q.setCorrectCount(0);
            q.setStatus("published");
            questionMapper.insert(q);
            return q;
        } catch (Exception e) {
            log.error("AI 题目生成失败, kpId={}", kpId, e);
            return null;
        }
    }

    private String formatEvidence(EvidenceRetriever.RagClient.RagResponse rag) {
        if (rag == null || rag.sources() == null || rag.sources().isEmpty()) {
            return "（无相关证据片段）";
        }
        return rag.sources().stream()
                .map(s -> "- " + (s.quote() != null ? s.quote() : ""))
                .collect(Collectors.joining("\n"));
    }

    // ==================== 查询 ====================

    @Override
    public PracticeSessionDTO getSession(String sessionId, String userId) {
        PracticeSession session = practiceSessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        List<PracticeSessionItem> items = practiceSessionItemMapper.listBySession(sessionId);
        List<String> questionIds = items.stream().map(PracticeSessionItem::getQuestionId).toList();
        Map<String, Question> questionMap = questionIds.isEmpty()
                ? Collections.emptyMap()
                : questionMapper.selectBatchIds(questionIds).stream()
                .collect(Collectors.toMap(Question::getId, q -> q));

        return toSessionDTO(session, items, questionMap);
    }

    @Override
    public Page<PracticeSessionSummaryDTO> listSessions(String userId, String courseId, int page, int size) {
        Page<PracticeSession> p = new Page<>(page, size);
        Page<PracticeSession> result = practiceSessionMapper.listByUser(userId, courseId, p);

        Page<PracticeSessionSummaryDTO> dtoPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        dtoPage.setRecords(result.getRecords().stream().map(this::toSummaryDTO).toList());
        return dtoPage;
    }

    // ==================== 两步法答题 ====================

    @Override
    @Transactional
    public void recordItemAnswer(String sessionId, String itemId, String userId, RecordItemAnswerRequest req) {
        PracticeSession session = practiceSessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        if (Boolean.TRUE.equals(session.getCompleted())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "会话已结束");
        }

        PracticeSessionItem item = practiceSessionItemMapper.selectById(itemId);
        if (item == null || !item.getSessionId().equals(sessionId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "题项不存在");
        }

        if (item.getAttemptId() != null) {
            return;
        }

        item.setAttemptId(req.getAttemptId());
        item.setIsCorrect(req.getIsCorrect());
        practiceSessionItemMapper.updateById(item);
    }

    // ==================== 结束会话 ====================

    @Override
    @Transactional
    public void completeSession(String sessionId, String userId) {
        PracticeSession session = practiceSessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        if (Boolean.TRUE.equals(session.getCompleted())) return;

        List<PracticeSessionItem> items = practiceSessionItemMapper.listBySession(sessionId);

        int correct = (int) items.stream().filter(i -> Boolean.TRUE.equals(i.getIsCorrect())).count();

        Set<String> attemptIds = items.stream()
                .map(PracticeSessionItem::getAttemptId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        int totalDuration = 0;
        if (!attemptIds.isEmpty()) {
            List<QuestionAttempt> attempts = questionAttemptMapper.selectBatchIds(attemptIds);
            totalDuration = attempts.stream()
                    .mapToInt(a -> a.getDurationSeconds() != null ? a.getDurationSeconds() : 0)
                    .sum();
        }

        session.setCorrectCount(correct);
        session.setTotalDurationSeconds(totalDuration);
        session.setCompleted(true);
        session.setCompletedAt(LocalDateTime.now());
        practiceSessionMapper.updateById(session);
    }

    // ==================== AI 评估 ====================

    @Override
    public void evaluateSession(String sessionId, String userId, SseEmitter emitter) {
        try {
            PracticeSession session = practiceSessionMapper.selectById(sessionId);
            if (session == null || !session.getUserId().equals(userId)) {
                sendEvalError(emitter, "会话不存在");
                return;
            }

            if (session.getEvaluation() != null && !session.getEvaluation().isBlank()) {
                emitter.send(SseEmitter.event().name("chunk").data(session.getEvaluation()));
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
                return;
            }

            String evalInput = buildSessionSummaryText(session, userId);

            StringBuilder full = new StringBuilder();
            evalClient.prompt()
                    .system(PRACTICE_EVAL_SYSTEM_PROMPT)
                    .user(evalInput)
                    .stream()
                    .content()
                    .doOnNext(chunk -> {
                        full.append(chunk);
                        try {
                            emitter.send(SseEmitter.event().name("chunk").data(chunk));
                        } catch (Exception ignored) {
                        }
                    })
                    .blockLast();

            if (full.isEmpty()) {
                sendEvalError(emitter, "评估生成失败");
                return;
            }

            boolean persistFailed = false;
            try {
                practiceSessionMapper.updateEvaluation(sessionId, full.toString());
            } catch (Exception e) {
                log.warn("Persist practice evaluation failed: {}", e.getMessage());
                persistFailed = true;
            }

            if (persistFailed) {
                emitter.send(SseEmitter.event().name("persist_failed")
                        .data("评估已生成，但保存失败，刷新页面后可能需要重新生成"));
            }
            emitter.send(SseEmitter.event().name("done").data(""));
            emitter.complete();
        } catch (Exception e) {
            log.error("evaluateSession failed: sessionId={}", sessionId, e);
            sendEvalError(emitter, "评估失败: " + e.getMessage());
        }
    }

    private String buildSessionSummaryText(PracticeSession session, String userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("【练习会话数据】\n");
        sb.append("会话类型：").append(typeLabel(session.getSessionType())).append("\n");
        sb.append("题目总数：").append(session.getQuestionCount()).append("\n");
        sb.append("正确数：").append(session.getCorrectCount()).append("\n");
        int answered = session.getCorrectCount();
        double accuracy = session.getQuestionCount() > 0
                ? (double) session.getCorrectCount() / session.getQuestionCount() : 0;
        sb.append("正确率：").append(String.format("%.1f%%", accuracy * 100)).append("\n");
        sb.append("用时：").append(session.getTotalDurationSeconds()).append(" 秒\n");
        if (session.getAiGeneratedCount() != null && session.getAiGeneratedCount() > 0) {
            sb.append("AI 新生成题目：").append(session.getAiGeneratedCount()).append(" 题\n");
        }
        sb.append("\n【各题作答】\n");

        List<PracticeSessionItem> items = practiceSessionItemMapper.listBySession(session.getId());
        List<String> questionIds = items.stream().map(PracticeSessionItem::getQuestionId).toList();
        Map<String, Question> questionMap = questionIds.isEmpty()
                ? Collections.emptyMap()
                : questionMapper.selectBatchIds(questionIds).stream()
                .collect(Collectors.toMap(Question::getId, q -> q));
        Map<String, String> kpNameMap = loadKpNames(questionMap.values());

        int idx = 1;
        for (PracticeSessionItem item : items) {
            Question q = questionMap.get(item.getQuestionId());
            sb.append("第").append(idx++).append("题");
            if (q != null) {
                sb.append("（").append(typeLabel(q.getQuestionType())).append("）");
                if (q.getKpId() != null && kpNameMap.containsKey(q.getKpId())) {
                    sb.append("【").append(kpNameMap.get(q.getKpId())).append("】");
                }
                sb.append("：\n");
                sb.append("题目：").append(q.getTitle()).append("\n");
                sb.append("正确答案：").append(q.getAnswerJson()).append("\n");
            } else {
                sb.append("：\n题目：（未知题目）\n");
            }
            if (item.getIsCorrect() == null) {
                sb.append("结果：未作答\n\n");
            } else if (item.getIsCorrect()) {
                sb.append("结果：✓ 正确\n\n");
            } else {
                sb.append("结果：✗ 错误\n\n");
            }
        }
        return sb.toString();
    }

    private void sendEvalError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(message));
            emitter.complete();
        } catch (Exception ignored) {
        }
    }

    // ==================== 辅助方法 ====================

    private List<PracticeSessionItem> insertItems(String sessionId, List<Question> questions) {
        List<PracticeSessionItem> items = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            PracticeSessionItem item = new PracticeSessionItem();
            item.setSessionId(sessionId);
            item.setQuestionId(questions.get(i).getId());
            item.setSortOrder(i + 1);
            item.setIsCorrect(null);
            practiceSessionItemMapper.insert(item);
            items.add(item);
        }
        return items;
    }

    private Map<String, PracticeSessionItem> buildItemMap(List<PracticeSessionItem> items) {
        return items.stream()
                .collect(Collectors.toMap(PracticeSessionItem::getQuestionId, i -> i, (a, b) -> a));
    }

    private PracticeSessionDTO toSessionDTOFromQuestions(PracticeSession session, List<Question> questions,
                                            Map<String, PracticeSessionItem> itemMap) {
        PracticeSessionDTO dto = baseDTO(session);
        Map<String, String> kpNameMap = loadKpNames(questions);
        List<SessionQuestionDTO> questionDTOs = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            SessionQuestionDTO qd = new SessionQuestionDTO();
            qd.setSortOrder(i + 1);
            populateQuestionDTO(qd, q);
            if (q.getKpId() != null) qd.setKpName(kpNameMap.get(q.getKpId()));
            PracticeSessionItem item = itemMap.get(q.getId());
            qd.setItemId(item != null ? item.getId() : null);
            qd.setAnswered(item != null && item.getAttemptId() != null);
            qd.setIsCorrect(item != null ? item.getIsCorrect() : null);
            questionDTOs.add(qd);
        }
        dto.setQuestions(questionDTOs);
        return dto;
    }

    private void populateQuestionDTO(SessionQuestionDTO qd, Question q) {
        if (q == null) return;
        qd.setQuestionId(q.getId());
        qd.setQuestionType(q.getQuestionType());
        qd.setDifficulty(q.getDifficulty());
        qd.setTitle(q.getTitle());
        if (q.getOptionsJson() != null) {
            qd.setOptions(parseJson(q.getOptionsJson(), new TypeReference<List<Map<String, String>>>() {}));
        }
        qd.setKpId(q.getKpId());
        try {
            if (q.getAnswerJson() != null) {
                qd.setCorrectAnswer(objectMapper.readTree(q.getAnswerJson()));
            }
        } catch (Exception ignored) {}
        qd.setExplanation(q.getExplanation());
    }

    private PracticeSessionDTO toSessionDTO(PracticeSession session, List<PracticeSessionItem> items,
                                            Map<String, Question> questionMap) {
        PracticeSessionDTO dto = baseDTO(session);
        Map<String, String> kpNameMap = loadKpNames(questionMap.values());

        // 批量加载 attempts 以获取用户作答
        Set<String> attemptIds = items.stream()
                .map(PracticeSessionItem::getAttemptId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, QuestionAttempt> attemptMap = attemptIds.isEmpty()
                ? Collections.emptyMap()
                : questionAttemptMapper.selectBatchIds(attemptIds).stream()
                .collect(Collectors.toMap(QuestionAttempt::getId, a -> a));

        List<SessionQuestionDTO> questionDTOs = new ArrayList<>();
        for (PracticeSessionItem item : items) {
            Question q = questionMap.get(item.getQuestionId());
            SessionQuestionDTO qd = new SessionQuestionDTO();
            qd.setItemId(item.getId());
            qd.setSortOrder(item.getSortOrder());
            if (q != null) {
                populateQuestionDTO(qd, q);
                if (q.getKpId() != null) qd.setKpName(kpNameMap.get(q.getKpId()));
            } else {
                qd.setQuestionId(item.getQuestionId());
            }
            qd.setAnswered(item.getAttemptId() != null);
            qd.setIsCorrect(item.getIsCorrect());
            // 填充用户作答
            if (item.getAttemptId() != null) {
                QuestionAttempt att = attemptMap.get(item.getAttemptId());
                if (att != null && att.getSelectedAnswer() != null) {
                    qd.setUserAnswer(parseUserAnswer(att.getSelectedAnswer()));
                }
            }
            questionDTOs.add(qd);
        }
        dto.setQuestions(questionDTOs);
        return dto;
    }

    private Object parseUserAnswer(String selectedAnswer) {
        if (selectedAnswer == null) return null;
        try {
            JsonNode node = objectMapper.readTree(selectedAnswer);
            if (node.isArray()) {
                List<String> list = new ArrayList<>();
                for (JsonNode n : node) list.add(n.asText());
                return list;
            }
            return node.asText();
        } catch (Exception e) {
            return selectedAnswer;
        }
    }

    private PracticeSessionDTO baseDTO(PracticeSession session) {
        PracticeSessionDTO dto = new PracticeSessionDTO();
        dto.setId(session.getId());
        dto.setSessionType(session.getSessionType());
        dto.setQuestionCount(session.getQuestionCount());
        dto.setCorrectCount(session.getCorrectCount());
        dto.setTotalDurationSeconds(session.getTotalDurationSeconds());
        dto.setCompleted(session.getCompleted());
        dto.setCreatedAt(session.getCreatedAt() != null ? session.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null);
        dto.setCompletedAt(session.getCompletedAt() != null ? session.getCompletedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null);
        dto.setAiGeneratedCount(session.getAiGeneratedCount());
        dto.setEvaluation(session.getEvaluation());
        return dto;
    }

    private PracticeSessionSummaryDTO toSummaryDTO(PracticeSession session) {
        PracticeSessionSummaryDTO dto = new PracticeSessionSummaryDTO();
        dto.setId(session.getId());
        dto.setSessionType(session.getSessionType());
        dto.setQuestionCount(session.getQuestionCount());
        dto.setCorrectCount(session.getCorrectCount());
        dto.setTotalDurationSeconds(session.getTotalDurationSeconds());
        dto.setCompleted(session.getCompleted());
        dto.setCreatedAt(session.getCreatedAt() != null ? session.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null);
        dto.setCompletedAt(session.getCompletedAt() != null ? session.getCompletedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null);
        dto.setAccuracyRate(session.getQuestionCount() != null && session.getQuestionCount() > 0
                ? (double) (session.getCorrectCount() != null ? session.getCorrectCount() : 0) / session.getQuestionCount()
                : 0);
        return dto;
    }

    private Map<String, String> loadKpNames(Collection<Question> questions) {
        Set<String> kpIds = questions.stream()
                .map(Question::getKpId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return loadKpNamesByIds(kpIds);
    }

    private Map<String, String> loadKpNamesByIds(Set<String> kpIds) {
        if (kpIds.isEmpty()) return Collections.emptyMap();
        List<CourseKnowledgePoint> kps = courseKpMapper.selectBatchIds(kpIds);
        return kps.stream().collect(Collectors.toMap(CourseKnowledgePoint::getId, CourseKnowledgePoint::getName));
    }

    private String typeLabel(String type) {
        if (type == null) return "未知";
        return switch (type) {
            case "single_choice" -> "单选题";
            case "multiple_choice" -> "多选题";
            case "true_false" -> "判断题";
            case "fill_blank" -> "填空题";
            case "short_answer" -> "简答题";
            case "code" -> "编程题";
            default -> type;
        };
    }

    // ==================== 练习统计 ====================

    @Override
    public PracticeStatsDTO getStats(String userId, String courseId) {
        // 1. 累计刷题数（仅统计已作答的题项）
        int totalAnswered = practiceSessionItemMapper.countAnsweredByUserAndCourse(userId, courseId);

        // 2. 平均正确率（仅统计已完成的会话）
        int totalCorrect = practiceSessionMapper.sumCorrectCount(userId, courseId);
        int totalQuestions = practiceSessionMapper.sumQuestionCount(userId, courseId);
        BigDecimal avgAccuracy = totalQuestions > 0
                ? BigDecimal.valueOf(totalCorrect).divide(BigDecimal.valueOf(totalQuestions), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // 3. 连续练习天数（复用 daily_learning_logs）
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(364);
        List<DailyLearningLog> logs = dailyLearningLogMapper.findByDateRange(userId, courseId, start, today);
        int currentStreak = computeStreak(logs, today);

        // 4. 待复习错题数（最新作答为错的去重题数）
        int wrongCount = questionAttemptMapper.countWrongQuestions(userId, courseId);

        return PracticeStatsDTO.builder()
                .totalAnswered(totalAnswered)
                .avgAccuracy(avgAccuracy)
                .currentStreak(currentStreak)
                .wrongQuestionCount(wrongCount)
                .build();
    }

    /** 从 daily_learning_logs 计算当前连续天数 */
    private int computeStreak(List<DailyLearningLog> logs, LocalDate today) {
        Set<LocalDate> activeDays = logs.stream()
                .filter(l -> l.getLearningSeconds() != null && l.getLearningSeconds() > 0)
                .map(DailyLearningLog::getLogDate)
                .collect(Collectors.toSet());
        int streak = 0;
        LocalDate cursor = today;
        while (activeDays.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    private <T> T parseJson(String json, TypeReference<T> typeRef) {
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            return null;
        }
    }
}
