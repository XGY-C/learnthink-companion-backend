package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.plan.ActivitySubmitRequest;
import com.learnthink.common.dto.plan.ActivitySubmitResponse;
import com.learnthink.common.dto.plan.PlanResponse;
import com.learnthink.common.exception.BusinessException;
import com.learnthink.core.config.PromptLoader;
import com.learnthink.core.domain.entity.*;
import com.learnthink.core.repository.*;
import com.learnthink.core.service.PlanService;
import com.learnthink.core.service.PushService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 学习路径服务实现 (v3.0)
 */
@Slf4j
@Service
public class PlanServiceImpl implements PlanService {

    private static final double QUIZ_PASS_THRESHOLD = 0.7;
    private static final double RETRY_FLOOR = 0.5;
    private static final int MAX_RETRIES = 2;

    private final LearningPlanMapper planMapper;
    private final LearningPlanVersionMapper planVersionMapper;
    private final SubPlanMapper subPlanMapper;
    private final QuizAttemptMapper quizAttemptMapper;
    private final LearningEventMapper learningEventMapper;
    private final ProfileVersionMapper profileVersionMapper;
    private final ResourceItemMapper resourceItemMapper;
    private final ResourcePackMapper resourcePackMapper;
    private final LearningRecordMapper learningRecordMapper;
    private final ObjectMapper objectMapper;
    private final PushService pushService;
    private final ChatClient shortAnswerClient;
    private final PromptLoader promptLoader;

    public PlanServiceImpl(LearningPlanMapper planMapper,
                           LearningPlanVersionMapper planVersionMapper,
                           SubPlanMapper subPlanMapper,
                           QuizAttemptMapper quizAttemptMapper,
                           LearningEventMapper learningEventMapper,
                           ProfileVersionMapper profileVersionMapper,
                           ResourceItemMapper resourceItemMapper,
                           ResourcePackMapper resourcePackMapper,
                           LearningRecordMapper learningRecordMapper,
                           ObjectMapper objectMapper,
                           PushService pushService,
                           @Qualifier("chatChatClientBuilder") ChatClient.Builder chatClientBuilder,
                           PromptLoader promptLoader) {
        this.planMapper = planMapper;
        this.planVersionMapper = planVersionMapper;
        this.subPlanMapper = subPlanMapper;
        this.quizAttemptMapper = quizAttemptMapper;
        this.learningEventMapper = learningEventMapper;
        this.profileVersionMapper = profileVersionMapper;
        this.resourceItemMapper = resourceItemMapper;
        this.resourcePackMapper = resourcePackMapper;
        this.learningRecordMapper = learningRecordMapper;
        this.objectMapper = objectMapper;
        this.pushService = pushService;
        this.shortAnswerClient = chatClientBuilder.build();
        this.promptLoader = promptLoader;
    }

    // ==================== 查询 ====================

    @Override
    public PlanResponse getCurrentPlan(String userId, String courseId) {
        LearningPlan plan = planMapper.findByUserIdAndCourseId(userId, courseId);
        if (plan == null) {
            return null;
        }
        return buildPlanResponse(plan);
    }

    @Override
    public PlanResponse.SubPlanDto getModuleSubPlan(String userId, String planId, String moduleId) {
        SubPlan subPlan = subPlanMapper.findByPlanIdAndModuleId(planId, moduleId);
        if (subPlan == null) {
            return null;
        }
        return parseSubPlanDto(subPlan);
    }

    // ==================== Activity 提交 ====================

    @Override
    @Transactional
    public ActivitySubmitResponse submitActivity(String userId, String activityId, ActivitySubmitRequest request) {
        Container ctx = findActivityContainer(userId, activityId);
        if (ctx == null) {
            throw new IllegalArgumentException("Activity not found: " + activityId);
        }

        if ("sequential".equals(ctx.plan().getLockMode())
                && "locked".equals(ctx.activity().get("status"))) {
            throw new BusinessException("该活动尚未解锁，请先完成前置活动");
        }

        Map<String, Object> activity = ctx.activity();
        String type = (String) activity.get("type");
        Map<String, Object> criteria = getCompletionCriteria(activity);
        double threshold = criteria.get("threshold") instanceof Number n ? n.doubleValue() : QUIZ_PASS_THRESHOLD;

        if ("quiz".equals(type) || (request.getAnswers() != null && !request.getAnswers().isEmpty())) {
            return submitQuizActivity(userId, ctx, activity, threshold, request);
        } else {
            return submitNonQuizActivity(userId, ctx, activity, type, request);
        }
    }

    private ActivitySubmitResponse submitQuizActivity(String userId, Container ctx,
                                                       Map<String, Object> activity,
                                                       double threshold,
                                                       ActivitySubmitRequest request) {
        String activityId = (String) activity.get("activity_id");
        String moduleId = (String) ctx.subPlan().getModuleId();
        Map<String, Object> criteria = getCompletionCriteria(activity);

        // 0. 加载 quiz ResourceItem 获取正确答案
        List<QuestionDef> questions = loadQuizQuestions(activity);

        // 1. 评分
        ScoreResult score = scoreQuiz(request, questions);
        List<String> weakTags = score.weakTags();

        // 2. 写 quiz_attempts
        QuizAttempt attempt = new QuizAttempt();
        attempt.setUserId(userId);
        attempt.setCourseId(ctx.plan().getCourseId());
        attempt.setTopic((String) activity.get("title"));
        attempt.setActivityId(activityId);
        attempt.setScore(BigDecimal.valueOf(score.score()));
        writeAttemptAnswers(attempt, request, weakTags, score.questionResults());
        quizAttemptMapper.insert(attempt);

        // 3. 写 learning_events
        writeLearningEvent(userId, "quiz_submitted", Map.of(
                "activity_id", activityId,
                "module_id", moduleId,
                "score", score.score(),
                "weak_tags", weakTags
        ));

        // 3.5 薄弱点推送检查（quiz 得分低于阈值且存在弱标签）
        if (score.score() < threshold && !weakTags.isEmpty() && pushService != null) {
            try {
                for (String weakTag : weakTags) {
                    // 查找匹配该薄弱标签的资源包
                    List<ResourcePack> matchedPacks = findPacksByTopic(
                            userId, ctx.plan().getCourseId(), weakTag);
                    for (ResourcePack pack : matchedPacks) {
                        pushService.notifyWeaknessFound(userId, ctx.plan().getCourseId(),
                                weakTag, pack.getId());
                        log.info("Weakness push triggered: userId={}, weakTag={}, packId={}",
                                userId, weakTag, pack.getId());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to trigger weakness push: {}", e.getMessage());
            }
        }

        // 4. 更新 activity 的 result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("score", score.score());
        result.put("time_spent", request.getDurationSeconds() != null ? (double) request.getDurationSeconds() : 0);
        result.put("completed_at", java.time.Instant.now().toString());
        result.put("weak_tags", weakTags);
        activity.put("result", result);

        double s = score.score;

        // 达标
        if (s >= threshold) {
            criteria.put("met", true);
            activity.put("status", "completed");
            activity.put("retry_count", 0);
            writeLearningRecords(ctx, activity, "completed", request.getDurationSeconds());
            saveSubPlan(ctx);
            updateModuleStatus(ctx);
            recomputeModuleMastery(ctx);
            unlockAfterActivityComplete(ctx, activityId);

            return ActivitySubmitResponse.builder()
                    .activityId(activityId)
                    .activityCompleted(true)
                    .status("completed")
                    .score(s)
                    .weakTags(weakTags)
                    .moduleStatus(getModuleStatus(ctx, moduleId))
                    .moduleMastery(getModuleMastery(ctx, moduleId))
                    .questionResults(score.questionResults())
                    .build();
        }

        // 严重未达标 → 回退重学
        if (s < threshold * RETRY_FLOOR) {
            criteria.put("met", false);
            activity.put("status", "failed");

            ActivitySubmitResponse.AutoActionDto autoAction = insertFallbackLearnQuiz(ctx, activity, weakTags, threshold);

            writeLearningRecords(ctx, activity, "failed", request.getDurationSeconds());
            saveSubPlan(ctx);
            updateModuleStatus(ctx);

            return ActivitySubmitResponse.builder()
                    .activityId(activityId)
                    .activityCompleted(false)
                    .status("failed")
                    .score(s)
                    .weakTags(weakTags)
                    .retryCount(getRetryCount(activity))
                    .retryAllowed(false)
                    .retriesRemaining(0)
                    .moduleStatus(getModuleStatus(ctx, moduleId))
                    .autoAction(autoAction)
                    .questionResults(score.questionResults())
                    .build();
        }

        // 边缘未达标
        int retryCount = getRetryCount(activity);
        retryCount++;
        activity.put("retry_count", retryCount);

        if (retryCount >= MAX_RETRIES) {
            // 重试用尽 → 回退重学
            criteria.put("met", false);
            activity.put("status", "failed");

            ActivitySubmitResponse.AutoActionDto autoAction = insertFallbackLearnQuiz(ctx, activity, weakTags, threshold);

            writeLearningRecords(ctx, activity, "failed", request.getDurationSeconds());
            saveSubPlan(ctx);
            updateModuleStatus(ctx);

            return ActivitySubmitResponse.builder()
                    .activityId(activityId)
                    .activityCompleted(false)
                    .status("failed")
                    .score(s)
                    .weakTags(weakTags)
                    .retryCount(retryCount)
                    .retryAllowed(false)
                    .retriesRemaining(0)
                    .moduleStatus(getModuleStatus(ctx, moduleId))
                    .autoAction(autoAction)
                    .questionResults(score.questionResults())
                    .build();
        }

        // 允许重试
        writeLearningRecords(ctx, activity, "in_progress", request.getDurationSeconds());
        saveSubPlan(ctx);
        updateModuleStatus(ctx);

        return ActivitySubmitResponse.builder()
                .activityId(activityId)
                .activityCompleted(false)
                .status("in_progress")
                .score(s)
                .weakTags(weakTags)
                .retryCount(retryCount)
                .retryAllowed(true)
                .retriesRemaining(MAX_RETRIES - retryCount)
                .moduleStatus(getModuleStatus(ctx, moduleId))
                .questionResults(score.questionResults())
                .build();
    }

    private ActivitySubmitResponse submitNonQuizActivity(String userId, Container ctx,
                                                          Map<String, Object> activity,
                                                          String type,
                                                          ActivitySubmitRequest request) {
        String activityId = (String) activity.get("activity_id");
        String moduleId = (String) ctx.subPlan().getModuleId();
        Map<String, Object> criteria = getCompletionCriteria(activity);
        int estMinutes = activity.get("estimated_minutes") instanceof Number n ? n.intValue() : 25;

        boolean completed;
        if ("explore".equals(type)) {
            // explore: 打开即算完成
            completed = true;
        } else {
            // learn: time >= estimated × 0.6 AND interaction detected
            int durationSeconds = request.getDurationSeconds() != null ? request.getDurationSeconds() : 0;
            boolean timeOk = durationSeconds >= estMinutes * 60 * 0.6;
            boolean interacted = request.getInteractionDetected() != null && request.getInteractionDetected();
            completed = timeOk && interacted;
        }

        if (completed) {
            criteria.put("met", true);
            activity.put("status", "completed");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("time_spent", request.getDurationSeconds() != null ? (double) request.getDurationSeconds() : 0);
            result.put("completed_at", java.time.Instant.now().toString());
            activity.put("result", result);
        }

        String resourceStatus = completed ? "completed" : "in_progress";
        if (activity.get("status") != null && "failed".equals(activity.get("status"))) {
            resourceStatus = "failed";
        }
        writeLearningRecords(ctx, activity, resourceStatus, request.getDurationSeconds());
        saveSubPlan(ctx);
        updateModuleStatus(ctx);
        unlockAfterActivityComplete(ctx, activityId);

        writeLearningEvent(userId, type.equals("explore") ? "resource_opened" : "resource_opened", Map.of(
                "activity_id", activityId,
                "module_id", moduleId,
                "type", type,
                "completed", completed
        ));

        return ActivitySubmitResponse.builder()
                .activityId(activityId)
                .activityCompleted(completed)
                .status((String) activity.get("status"))
                .moduleStatus(getModuleStatus(ctx, moduleId))
                .build();
    }

    // ==================== Quiz 资源加载 ====================

    private List<QuestionDef> loadQuizQuestions(Map<String, Object> activity) {
        Map<String, Object> resource = castMap(activity.get("resource"));

        // 兼容 learn 活动中 quiz 作为 resources 数组元素的情况
        // （sub_plan 中 quiz 不是独立 activity，而是 learn 的 resources[] 中的一个 resource_type）
        if (resource == null) {
            List<Map<String, Object>> resources = castMapList(activity.get("resources"));
            for (Map<String, Object> r : resources) {
                if ("quiz".equals(r.get("resource_type"))) {
                    resource = r;
                    log.info("Found quiz resource in resources array for activity_id={}, packId={}",
                            activity.get("activity_id"), r.get("resource_pack_id"));
                    break;
                }
            }
        }

        if (resource == null) {
            log.warn("Quiz activity has no resource field and no quiz resource in resources array, activity_id={}",
                    activity.get("activity_id"));
            return List.of();
        }

        String packId = (String) resource.get("resource_pack_id");
        if (packId == null) {
            log.warn("Quiz activity resource has no resource_pack_id, resource keys={}", resource.keySet());
            return List.of();
        }

        try {
            List<ResourceItem> items = resourceItemMapper.selectList(
                new LambdaQueryWrapper<ResourceItem>()
                    .eq(ResourceItem::getPackId, packId)
                    .eq(ResourceItem::getType, "quiz")
            );
            if (items.isEmpty()) {
                log.warn("No quiz ResourceItem found for packId={}", packId);
                return List.of();
            }
            ResourceItem quizItem = items.get(0);
            Map<String, Object> meta = objectMapper.readValue(quizItem.getMetadataJson(), mapType());
            String contentJson = (String) meta.get("content");
            if (contentJson == null) {
                log.warn("Quiz ResourceItem has no content in metadata: itemId={}", quizItem.getId());
                return List.of();
            }
            Map<String, Object> quizObj = objectMapper.readValue(contentJson, mapType());
            List<Map<String, Object>> questionList = castMapList(quizObj.get("questions"));
            List<QuestionDef> result = questionList.stream()
                .map(q -> {
                    Map<String, Object> tags = castMap(q.get("tags"));
                    return new QuestionDef(
                        qId(q),
                        (String) q.get("type"),
                        (String) q.get("content"),
                        (String) q.get("answer"),
                        tags != null ? castStringList(tags.get("mistakes")) : List.of(),
                        tags != null ? castStringList(tags.get("knowledge")) : List.of()
                    );
                })
                .toList();
            log.info("Loaded {} quiz questions, IDs={}", result.size(),
                    result.stream().map(q -> q.questionId).toList());
            return result;
        } catch (Exception e) {
            log.error("Failed to load quiz questions for activity", e);
            return List.of();
        }
    }

    /** 兼容 generator 输出的 id(整数) 和手动构造的 question_id(字符串) */
    private String qId(Map<String, Object> q) {
        String id = (String) q.get("question_id");
        if (id != null) return id;
        Object rawId = q.get("id");
        return rawId != null ? String.valueOf(rawId) : null;
    }

    // ==================== 评分逻辑 ====================

    private ScoreResult scoreQuiz(ActivitySubmitRequest request, List<QuestionDef> questions) {
        if (request.getAnswers() == null || request.getAnswers().isEmpty()) {
            return new ScoreResult(0.0, 0, 0, List.of(), List.of());
        }

        // Build lookup: questionId → QuestionDef
        Map<String, QuestionDef> lookup = new LinkedHashMap<>();
        for (QuestionDef q : questions) {
            if (q.questionId != null) lookup.put(q.questionId, q);
        }

        int correct = 0;
        int totalQuestions = questions.size();
        // 使用题目总数作为分母，而非仅提交的答案数，避免未作答题目拉高正确率
        int total = totalQuestions > 0 ? totalQuestions : request.getAnswers().size();
        double weightedScore = 0;
        Set<String> weakTagSet = new LinkedHashSet<>();
        LinkedHashMap<String, String> resultMap = new LinkedHashMap<>();
        List<ActivitySubmitRequest.AnswerItem> shortAnswerItems = new ArrayList<>();

        // First pass: score objective + fill-in-blank, collect short-answer items
        for (ActivitySubmitRequest.AnswerItem ans : request.getAnswers()) {
            QuestionDef q = lookup.get(ans.getQuestionId());
            if (q == null) {
                log.warn("Answer submitted for unknown question_id={}", ans.getQuestionId());
                resultMap.put(ans.getQuestionId(), "incorrect");
                continue;
            }

            if ("SHORT_ANSWER".equals(q.type)) {
                shortAnswerItems.add(ans);
                continue; // evaluate in second pass
            }

            String result = "incorrect";
            if (q.answer != null && ans.getAnswer() != null && !ans.getAnswer().isBlank()) {
                if ("FILL_IN_BLANK".equals(q.type)) {
                    // containment match
                    String userAns = ans.getAnswer().trim().toLowerCase();
                    String refAns = q.answer.trim().toLowerCase();
                    if (userAns.contains(refAns) || refAns.contains(userAns)) {
                        result = "correct";
                    }
                } else {
                    // SINGLE_CHOICE / TRUE_FALSE / MULTIPLE_CHOICE: exact match (case-insensitive)
                    if (q.answer.trim().equalsIgnoreCase(ans.getAnswer().trim())) {
                        result = "correct";
                    }
                }
            }

            if ("correct".equals(result)) {
                correct++;
                weightedScore += 1.0;
            } else {
                if (q.mistakeTags != null) weakTagSet.addAll(q.mistakeTags);
                if (q.knowledgeTags != null) weakTagSet.addAll(q.knowledgeTags);
            }
            resultMap.put(ans.getQuestionId(), result);
        }

        // Second pass: evaluate SHORT_ANSWER via LLM
        if (!shortAnswerItems.isEmpty()) {
            Map<String, String> saResults = evaluateShortAnswers(shortAnswerItems, lookup);
            for (ActivitySubmitRequest.AnswerItem ans : shortAnswerItems) {
                QuestionDef q = lookup.get(ans.getQuestionId());
                String r = saResults.getOrDefault(ans.getQuestionId(), "incorrect");
                if ("correct".equals(r)) {
                    correct++;
                    weightedScore += 1.0;
                } else if ("partial".equals(r)) {
                    weightedScore += 0.5;
                } else {
                    if (q != null) {
                        if (q.mistakeTags != null) weakTagSet.addAll(q.mistakeTags);
                        if (q.knowledgeTags != null) weakTagSet.addAll(q.knowledgeTags);
                    }
                }
                resultMap.put(ans.getQuestionId(), r);
            }
        }

        // Build questionResults: submitted answers + unanswered questions (all marked incorrect)
        List<ActivitySubmitResponse.QuestionResult> questionResults = new ArrayList<>();
        for (ActivitySubmitRequest.AnswerItem ans : request.getAnswers()) {
            String r = resultMap.getOrDefault(ans.getQuestionId(), "incorrect");
            questionResults.add(makeResult(ans.getQuestionId(), r));
        }
        // 补全未作答的题目，确保 questionResults 覆盖全部题目
        for (QuestionDef q : questions) {
            if (q.questionId != null && !resultMap.containsKey(q.questionId)) {
                questionResults.add(makeResult(q.questionId, "incorrect"));
            }
        }

        double score = total > 0 ? weightedScore / total : 0;
        List<String> weakTags = new ArrayList<>(weakTagSet);
        log.info("Quiz scored: {}/{} correct (weighted={}), score={}, weakTags={}, totalQuestions={}",
                correct, total, weightedScore, score, weakTags, totalQuestions);
        return new ScoreResult(score, correct, total, weakTags, questionResults);
    }

    private ActivitySubmitResponse.QuestionResult makeResult(String questionId, String result) {
        var r = new ActivitySubmitResponse.QuestionResult();
        r.setQuestionId(questionId);
        r.setResult(result);
        return r;
    }

    /** 批量 LLM 简答评判：一次调用评判所有简答题 */
    private Map<String, String> evaluateShortAnswers(
            List<ActivitySubmitRequest.AnswerItem> items,
            Map<String, QuestionDef> lookup) {
        StringBuilder prompt = new StringBuilder(promptLoader.get("evaluate/short_answer"));
        prompt.append("\n\n## 题目与学生答案\n");
        for (int i = 0; i < items.size(); i++) {
            ActivitySubmitRequest.AnswerItem ans = items.get(i);
            QuestionDef q = lookup.get(ans.getQuestionId());
            prompt.append(String.format("""
                    题目 %d:
                    问题: %s
                    参考答案: %s
                    学生答案: %s
                    """, i + 1, q != null ? q.content : "", q != null ? q.answer : "", ans.getAnswer()));
        }
        prompt.append("\n请按顺序输出每道题的评判结果，每行格式：题目索引|结果（correct/partial/incorrect）");

        try {
            String response = shortAnswerClient.prompt()
                .user(prompt.toString())
                .call()
                .content();
            log.info("Short-answer LLM evaluation raw:\n{}", response);
            return parseShortAnswerResults(response, items);
        } catch (Exception e) {
            log.error("Short-answer LLM evaluation failed", e);
            Map<String, String> fallback = new HashMap<>();
            for (var item : items) {
                fallback.put(item.getQuestionId(), "incorrect");
            }
            return fallback;
        }
    }

    private Map<String, String> parseShortAnswerResults(String response, List<ActivitySubmitRequest.AnswerItem> items) {
        Map<String, String> results = new HashMap<>();
        if (response == null || response.isBlank()) {
            for (var item : items) results.put(item.getQuestionId(), "incorrect");
            return results;
        }
        String[] lines = response.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\|");
            if (parts.length < 2) continue;
            try {
                int idx = Integer.parseInt(parts[0].trim());
                String result = parts[1].trim().toLowerCase();
                if (idx >= 1 && idx <= items.size()) {
                    if ("correct".equals(result) || "partial".equals(result) || "incorrect".equals(result)) {
                        results.put(items.get(idx - 1).getQuestionId(), result);
                    }
                }
            } catch (NumberFormatException ignored) {}
        }
        // fill missing items as incorrect
        for (var item : items) {
            results.putIfAbsent(item.getQuestionId(), "incorrect");
        }
        return results;
    }

    private void writeAttemptAnswers(QuizAttempt attempt, ActivitySubmitRequest request,
                                     List<String> weakTags,
                                     List<ActivitySubmitResponse.QuestionResult> questionResults) {
        try {
            List<Map<String, Object>> enriched = new ArrayList<>();
            Map<String, String> resultMap = new LinkedHashMap<>();
            if (questionResults != null) {
                for (var qr : questionResults) {
                    resultMap.put(qr.getQuestionId(), qr.getResult());
                }
            }
            for (var ans : request.getAnswers()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("question_id", ans.getQuestionId());
                entry.put("answer", ans.getAnswer());
                String r = resultMap.get(ans.getQuestionId());
                if (r != null) entry.put("result", r);
                enriched.add(entry);
            }
            attempt.setAnswersJson(objectMapper.writeValueAsString(enriched));
            attempt.setWeakTags(objectMapper.writeValueAsString(weakTags));
            attempt.setDurationSeconds(request.getDurationSeconds());
        } catch (Exception e) {
            log.error("Failed to serialize quiz answers", e);
        }
    }

    private record QuestionDef(String questionId, String type, String content, String answer, List<String> mistakeTags, List<String> knowledgeTags) {}
    private record ScoreResult(double score, int correct, int total, List<String> weakTags, List<ActivitySubmitResponse.QuestionResult> questionResults) {}

    // ==================== 智能评估 ====================

    private static final String QUIZ_EVAL_SYSTEM_PROMPT = """
            你是学思伴行（LearnThink Companion）的学习评估专家。
            请根据学生的本次做题数据，生成客观、专业的智能评估分析。

            【输出规范】
            严格按以下 Markdown 结构输出，不得增删章节，不得输出结构之外的内容：
            ## 表现概述
            <得分、正确率、等级（优秀/良好/合格/待改进）一句话概述>

            ## 掌握诊断
            - 已掌握：<本次做对题目反映的知识点>
            - 薄弱点：<本次做错或薄弱的知识点，结合错题说明为何薄弱>

            ## 错题分析
            <逐道错题分析：错在哪、混淆了什么、正确思路是什么；若全对则写"本次无错题">

            ## 改进建议
            1. <针对薄弱点的具体可操作建议，2-3 条>

            【要求】
            - 评语具体、指向本次作答，避免空话套话
            - 不要复述题目原文，引用关键点即可
            - 只输出评估，不要寒暄与解释
            """;

    @Override
    public void evaluateQuizActivity(String userId, String activityId, SseEmitter emitter) {
        try {
            Container ctx = findActivityContainer(userId, activityId);
            if (ctx == null) {
                sendEvalError(emitter, "未找到该学习活动");
                return;
            }
            Map<String, Object> activity = ctx.activity();
            boolean isStandaloneQuiz = "quiz".equals(activity.get("type"));

            List<QuestionDef> questions = loadQuizQuestions(activity);
            if (questions.isEmpty()) {
                sendEvalError(emitter, isStandaloneQuiz ? "未找到题目内容" : "该活动不含练习题，无法评估");
                return;
            }

            QuizAttempt attempt = quizAttemptMapper.selectOne(
                    new LambdaQueryWrapper<QuizAttempt>()
                            .eq(QuizAttempt::getUserId, userId)
                            .eq(QuizAttempt::getActivityId, activityId)
                            .orderByDesc(QuizAttempt::getCreatedAt)
                            .last("LIMIT 1"));
            if (attempt == null) {
                sendEvalError(emitter, "尚未提交作答，无法评估");
                return;
            }

            // 独立 quiz 活动：检查缓存，只评估一次；非独立 quiz（learn 中的 quiz 资源）：每次重新评估，不落库
            if (isStandaloneQuiz && attempt.getEvaluation() != null && !attempt.getEvaluation().isBlank()) {
                emitter.send(SseEmitter.event().name("chunk").data(attempt.getEvaluation()));
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
                return;
            }

            String evalInput = buildEvaluationInput(attempt, questions);

            StringBuilder full = new StringBuilder();
            shortAnswerClient.prompt()
                    .system(QUIZ_EVAL_SYSTEM_PROMPT)
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

            // 仅独立 quiz 活动落库保存评估结果；learn 中的 quiz 资源只展示不保存
            if (isStandaloneQuiz) {
                try {
                    attempt.setEvaluation(full.toString());
                    quizAttemptMapper.updateById(attempt);
                } catch (Exception e) {
                    log.warn("Persist quiz evaluation failed: {}", e.getMessage());
                }
            }

            emitter.send(SseEmitter.event().name("done").data(""));
            emitter.complete();
        } catch (Exception e) {
            log.error("evaluateQuizActivity failed: activityId={}", activityId, e);
            sendEvalError(emitter, "评估失败: " + e.getMessage());
        }
    }

    private String buildEvaluationInput(QuizAttempt attempt, List<QuestionDef> questions) {
        StringBuilder sb = new StringBuilder();
        sb.append("【学生作答数据】\n");
        sb.append("得分：").append(attempt.getScore()).append("\n");

        List<String> weakTags = parseStringList(attempt.getWeakTags());
        sb.append("薄弱标签：").append(weakTags.isEmpty() ? "无" : String.join("、", weakTags)).append("\n\n");
        sb.append("【各题作答】\n");

        List<Map<String, Object>> answers = parseAnswersJson(attempt.getAnswersJson());
        Map<String, QuestionDef> qLookup = new LinkedHashMap<>();
        for (QuestionDef q : questions) {
            if (q.questionId() != null) qLookup.put(q.questionId(), q);
        }

        int idx = 1;
        for (Map<String, Object> ans : answers) {
            String qid = String.valueOf(ans.getOrDefault("question_id", ""));
            String studentAns = String.valueOf(ans.getOrDefault("answer", ""));
            String result = String.valueOf(ans.getOrDefault("result", "incorrect"));
            QuestionDef q = qLookup.get(qid);

            sb.append("第").append(idx++).append("题");
            if (q != null) {
                sb.append("（").append(typeLabel(q.type())).append("）：\n");
                sb.append("题目：").append(q.content()).append("\n");
                sb.append("学生答案：").append(studentAns).append("\n");
                sb.append("正确答案：").append(q.answer()).append("\n");
            } else {
                sb.append("：\n题目：（未知题目）\n学生答案：").append(studentAns).append("\n");
            }
            sb.append("结果：")
                    .append("correct".equals(result) ? "✓ 正确"
                            : "partial".equals(result) ? "△ 部分正确"
                            : "✗ 错误")
                    .append("\n\n");
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

    private String typeLabel(String type) {
        if (type == null) return "题目";
        return switch (type) {
            case "SINGLE_CHOICE" -> "单选题";
            case "MULTIPLE_CHOICE" -> "多选题";
            case "TRUE_FALSE" -> "判断题";
            case "FILL_IN_BLANK" -> "填空题";
            case "SHORT_ANSWER" -> "简答题";
            default -> "题目";
        };
    }

    private List<Map<String, Object>> parseAnswersJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("parseAnswersJson failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    // ==================== 回退重学 ====================

    private ActivitySubmitResponse.AutoActionDto insertFallbackLearnQuiz(Container ctx,
                                                                          Map<String, Object> failedActivity,
                                                                          List<String> weakTags,
                                                                          double threshold) {
        List<Map<String, Object>> activities = castMapList(ctx.subPlanObj().get("activities"));
        int failedOrder = failedActivity.get("order") instanceof Number n ? n.intValue() : 0;
        String failedTitle = (String) failedActivity.get("title");
        String weakTagsStr = String.join("、", weakTags.stream().limit(3).toList());
        String baseId = (String) failedActivity.get("activity_id");

        // 查找同 sub-plan 中已有的 doc 资源包，复用其内容作为回顾材料
        String fallbackPackId = findExistingDocPackId(activities);
        boolean hasExistingDoc = fallbackPackId != null;

        // 插入 learn
        String learnId = baseId + "b_learn";
        Map<String, Object> learnActivity = new LinkedHashMap<>();
        learnActivity.put("activity_id", learnId);
        learnActivity.put("type", "learn");
        learnActivity.put("title", "回顾：" + weakTagsStr);
        learnActivity.put("description", "针对薄弱点的回顾学习");
        learnActivity.put("requires", new ArrayList<>());
        Map<String, Object> learnResource = new LinkedHashMap<>();
        learnResource.put("source", hasExistingDoc ? "matched" : "generated");
        learnResource.put("resource_pack_id", hasExistingDoc ? fallbackPackId : null);
        learnResource.put("resource_type", "doc");
        learnResource.put("generation_status", hasExistingDoc ? null : "pending");
        learnActivity.put("resource", learnResource);
        learnActivity.put("estimated_minutes", 20);
        learnActivity.put("order", failedOrder);
        learnActivity.put("completion_criteria", Map.of("type", "resource_open", "threshold", 10, "met", false));
        learnActivity.put("status", "ready");
        learnActivity.put("retry_count", 0);
        learnActivity.put("result", null);

        // 插入 quiz
        String quizId = baseId + "c_quiz";
        Map<String, Object> quizActivity = new LinkedHashMap<>();
        quizActivity.put("activity_id", quizId);
        quizActivity.put("type", "quiz");
        quizActivity.put("title", "重新检验：" + failedTitle);
        quizActivity.put("description", "检验回顾后的掌握情况");
        quizActivity.put("requires", List.of(learnId));
        Map<String, Object> quizResource = new LinkedHashMap<>();
        quizResource.put("source", "generated");
        quizResource.put("resource_pack_id", null);
        quizResource.put("resource_type", "quiz");
        quizResource.put("generation_status", "pending");
        quizActivity.put("resource", quizResource);
        quizActivity.put("estimated_minutes", 20);
        quizActivity.put("order", failedOrder + 1);
        quizActivity.put("completion_criteria", Map.of("type", "quiz_score", "threshold", threshold, "met", false));
        quizActivity.put("status", "locked");
        quizActivity.put("retry_count", 0);
        quizActivity.put("result", null);

        // 后移后续 activity 的 order
        for (Map<String, Object> a : activities) {
            int ord = a.get("order") instanceof Number n ? n.intValue() : 0;
            if (ord >= failedOrder) {
                a.put("order", ord + 2);
            }
            // requires 重定向
            List<String> reqs = castStringList(a.get("requires"));
            if (reqs != null && reqs.contains(baseId)) {
                a.put("requires", reqs.stream().map(r -> r.equals(baseId) ? quizId : r).toList());
            }
        }

        activities.add(learnActivity);
        activities.add(quizActivity);
        activities.sort(Comparator.comparingInt(a -> a.get("order") instanceof Number n ? n.intValue() : 0));

        // 记录 adjustments
        List<Map<String, Object>> adjustments = castMapList(ctx.subPlanObj().get("adjustments"));
        Map<String, Object> adj = new LinkedHashMap<>();
        adj.put("at", java.time.Instant.now().toString());
        adj.put("reason", "正确率 " + String.format("%.0f%%", getResultScore(failedActivity) * 100) +
                " 严重低于阈值(" + String.format("%.0f%%", threshold * 100) + ")，薄弱点：" + weakTagsStr + "。已插入回顾学习+重新检验");
        adj.put("diff", Map.of(
                "module_id", ctx.subPlan().getModuleId(),
                "failed_activity_id", baseId,
                "inserted_activities", List.of(learnId, quizId)
        ));
        adjustments.add(adj);
        ctx.subPlanObj().put("adjustments", adjustments);

        return ActivitySubmitResponse.AutoActionDto.builder()
                .type("fallback_relearn")
                .reason((String) adj.get("reason"))
                .insertedActivities(List.of(
                        ActivitySubmitResponse.InsertedActivityDto.builder().activityId(learnId).type("learn").title((String) learnActivity.get("title")).build(),
                        ActivitySubmitResponse.InsertedActivityDto.builder().activityId(quizId).type("quiz").title((String) quizActivity.get("title")).build()
                ))
                .build();
    }

    // ==================== Module 状态与指标 ====================

    private void updateModuleStatus(Container ctx) {
        List<Map<String, Object>> activities = castMapList(ctx.subPlanObj().get("activities"));
        String moduleId = (String) ctx.subPlan().getModuleId();

        // 记录旧状态，用于检测模块完成事件
        String oldStatus = getModuleStatus(ctx, moduleId);

        long nonExploreTotal = activities.stream().filter(a -> !"explore".equals(a.get("type"))).count();
        long nonExploreCompleted = activities.stream()
                .filter(a -> !"explore".equals(a.get("type")) && "completed".equals(a.get("status")))
                .count();
        long inProgress = activities.stream().filter(a -> "completed".equals(a.get("status"))).count();
        boolean hasQuizPassed = activities.stream()
                .filter(a -> "quiz".equals(a.get("type")) && "completed".equals(a.get("status")))
                .anyMatch(a -> {
                    Map<String, Object> c = castMap(a.get("completionCriteria"));
                    return c.get("met") instanceof Boolean b && b;
                });

        if (nonExploreCompleted >= nonExploreTotal && nonExploreTotal > 0 && hasQuizPassed) {
            updatePlanModuleStatus(ctx, moduleId, "completed");
        } else if (inProgress > 0) {
            updatePlanModuleStatus(ctx, moduleId, "in_progress");
        }

        // 模块刚刚变成 completed → 记录事件、检查下一模块资源并推送
        String newStatus = getModuleStatus(ctx, moduleId);
        if ("completed".equals(newStatus) && !"completed".equals(oldStatus)) {
            // 查找模块标题
            List<Map<String, Object>> modules = castMapList(ctx.planObj().get("modules"));
            String moduleTitle = moduleId;
            for (Map<String, Object> m : modules) {
                if (moduleId.equals(m.get("module_id"))) {
                    moduleTitle = (String) m.getOrDefault("title", moduleId);
                    break;
                }
            }
            writeLearningEvent(ctx.plan().getUserId(), "node_completed", Map.of(
                    "module_id", moduleId,
                    "title", moduleTitle,
                    "plan_id", ctx.plan().getId(),
                    "course_id", ctx.plan().getCourseId()
            ));
            tryPushPathNext(ctx, moduleId);
            unlockDependentModules(ctx, moduleId);
        }
    }

    /**
     * 模块完成后检查下一模块是否有 ready 资源，触发路径前进推送
     */
    private void tryPushPathNext(Container ctx, String completedModuleId) {
        try {
            List<Map<String, Object>> modules = castMapList(ctx.planObj().get("modules"));
            int currentIdx = -1;
            for (int i = 0; i < modules.size(); i++) {
                if (completedModuleId.equals(modules.get(i).get("module_id"))) {
                    currentIdx = i;
                    break;
                }
            }
            if (currentIdx < 0 || currentIdx + 1 >= modules.size()) return;

            Map<String, Object> nextModule = modules.get(currentIdx + 1);
            String nextSubPlanId = (String) nextModule.get("sub_plan_id");
            if (nextSubPlanId == null) return;

            SubPlan nextSp = subPlanMapper.selectById(nextSubPlanId);
            if (nextSp == null) return;

            // 查找下一模块中第一个 ready 的资源
            Map<String, Object> spObj = objectMapper.readValue(nextSp.getSubPlanJson(), mapType());
            List<Map<String, Object>> activities = castMapList(spObj.get("activities"));
            for (Map<String, Object> act : activities) {
                // 检查 resource 字段
                Map<String, Object> res = castMap(act.get("resource"));
                String packId = findReadyPackId(res);
                if (packId != null) {
                    pushService.notifyResourceReady(
                            ctx.plan().getUserId(), ctx.plan().getCourseId(),
                            packId, "push_path_next", null);
                    log.info("Path next push triggered: userId={}, nextModule={}, packId={}",
                            ctx.plan().getUserId(), nextModule.get("title"), packId);
                    return;
                }
                // 检查 resources 数组
                List<Map<String, Object>> resList = castMapList(act.get("resources"));
                for (Map<String, Object> r : resList) {
                    packId = findReadyPackId(r);
                    if (packId != null) {
                        pushService.notifyResourceReady(
                                ctx.plan().getUserId(), ctx.plan().getCourseId(),
                                packId, "push_path_next", null);
                        log.info("Path next push triggered: userId={}, nextModule={}, packId={}",
                                ctx.plan().getUserId(), nextModule.get("title"), packId);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to trigger path next push: {}", e.getMessage());
        }
    }

    /** 从 activity resource 条目中提取已就绪的资源包 ID */
    private String findReadyPackId(Map<String, Object> res) {
        if (res == null) return null;
        String packId = (String) res.get("resource_pack_id");
        String genStatus = (String) res.get("generation_status");
        // "ready" 表示已生成可直接使用；null 的 matched 资源也算就绪
        if (packId != null && !packId.isBlank()
                && (genStatus == null || "ready".equals(genStatus))) {
            return packId;
        }
        return null;
    }

    private void updatePlanModuleStatus(Container ctx, String moduleId, String status) {
        List<Map<String, Object>> modules = castMapList(ctx.planObj().get("modules"));
        for (Map<String, Object> m : modules) {
            if (moduleId.equals(m.get("module_id"))) {
                m.put("status", status);
                break;
            }
        }
        try {
            ctx.plan().setPlanJson(objectMapper.writeValueAsString(ctx.planObj()));
            planMapper.updatePlan(ctx.plan().getId(), ctx.plan().getCurrentVersion(), ctx.plan().getPlanJson());
        } catch (Exception e) {
            log.error("Failed to update plan module status", e);
        }
    }

    private void recomputeModuleMastery(Container ctx) {
        List<Map<String, Object>> activities = castMapList(ctx.subPlanObj().get("activities"));
        List<Map<String, Object>> quizActivities = activities.stream()
                .filter(a -> "quiz".equals(a.get("type")) && a.get("result") != null)
                .toList();

        if (quizActivities.isEmpty()) return;

        double weightedSum = 0;
        double totalWeight = 0;
        for (Map<String, Object> qa : quizActivities) {
            Map<String, Object> result = castMap(qa.get("result"));
            double score = result.get("score") instanceof Number n ? n.doubleValue() : 0;
            double weight = 1.0; // simplified: equal weight
            weightedSum += score * weight;
            totalWeight += weight;
        }
        double mastery = totalWeight > 0 ? weightedSum / totalWeight : 0;

        String moduleId = (String) ctx.subPlan().getModuleId();
        List<Map<String, Object>> modules = castMapList(ctx.planObj().get("modules"));
        for (Map<String, Object> m : modules) {
            if (moduleId.equals(m.get("module_id"))) {
                m.put("mastery", Math.round(mastery * 100.0) / 100.0);
                break;
            }
        }
        try {
            ctx.plan().setPlanJson(objectMapper.writeValueAsString(ctx.planObj()));
            planMapper.updatePlan(ctx.plan().getId(), ctx.plan().getCurrentVersion(), ctx.plan().getPlanJson());
        } catch (Exception e) {
            log.error("Failed to update module mastery", e);
        }
    }

    @Override
    public List<Map<String, Object>> getResourceStatus(String userId, String activityId, String moduleId) {
        Container ctx = findActivityContainer(userId, activityId, moduleId);
        if (ctx == null) return List.of();

        String planId = ctx.plan().getId();
        String resolvedModuleId = ctx.subPlan().getModuleId();

        List<LearningRecord> records = learningRecordMapper.findByActivity(userId, planId, resolvedModuleId, activityId);
        Map<String, LearningRecord> recordMap = new LinkedHashMap<>();
        for (LearningRecord r : records) {
            recordMap.put(r.getResourceType(), r);
        }

        List<Map<String, Object>> resources = castMapList(ctx.activity().get("resources"));
        if (resources.isEmpty()) {
            Map<String, Object> resource = castMap(ctx.activity().get("resource"));
            if (resource != null) resources = List.of(resource);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : resources) {
            String resourceType = (String) r.get("resource_type");
            if (resourceType == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("resource_type", resourceType);
            LearningRecord record = recordMap.get(resourceType);
            if (record != null) {
                item.put("status", record.getStatus());
                item.put("duration_seconds", record.getDurationSeconds());
                item.put("completed_at", record.getCompletedAt() != null ? record.getCompletedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null);
            } else {
                item.put("status", null);
                item.put("duration_seconds", null);
                item.put("completed_at", null);
            }
            result.add(item);
        }
        return result;
    }

    @Override
    public void updateResourceStatus(String userId, String activityId, String moduleId, String resourceType, String status, Integer durationSeconds) {
        Container ctx = findActivityContainer(userId, activityId, moduleId);
        if (ctx == null) return;

        List<Map<String, Object>> resources = castMapList(ctx.activity().get("resources"));
        Map<String, Object> targetResource = null;
        for (Map<String, Object> r : resources) {
            if (resourceType.equals(r.get("resource_type"))) {
                targetResource = r;
                break;
            }
        }
        if (targetResource == null) {
            Map<String, Object> resource = castMap(ctx.activity().get("resource"));
            if (resource != null && resourceType.equals(resource.get("resource_type"))) {
                targetResource = resource;
            }
        }
        if (targetResource == null) return;

        LearningRecord record = new LearningRecord();
        record.setUserId(userId);
        record.setCourseId(ctx.plan().getCourseId());
        record.setPlanId(ctx.plan().getId());
        record.setModuleId(ctx.subPlan().getModuleId());
        record.setActivityId(activityId);
        record.setResourcePackId((String) targetResource.get("resource_pack_id"));
        record.setResourceType(resourceType);
        record.setStatus(status);
        record.setDurationSeconds(durationSeconds != null ? durationSeconds : 0);
        if ("completed".equals(status)) {
            record.setCompletedAt(LocalDateTime.now());
        }
        learningRecordMapper.upsert(record);
    }

    private String getModuleStatus(Container ctx, String moduleId) {
        List<Map<String, Object>> modules = castMapList(ctx.planObj().get("modules"));
        return modules.stream()
                .filter(m -> moduleId.equals(m.get("module_id")))
                .findFirst()
                .map(m -> (String) m.get("status"))
                .orElse("ready");
    }

    private Double getModuleMastery(Container ctx, String moduleId) {
        List<Map<String, Object>> modules = castMapList(ctx.planObj().get("modules"));
        return modules.stream()
                .filter(m -> moduleId.equals(m.get("module_id")))
                .findFirst()
                .map(m -> m.get("mastery") instanceof Number n ? n.doubleValue() : null)
                .orElse(null);
    }

    // ==================== 辅助方法 ====================

    private Container findActivityContainer(String userId, String activityId) {
        return findActivityContainer(userId, activityId, null);
    }

    private Container findActivityContainer(String userId, String activityId, String moduleId) {
        List<LearningPlan> plans = planMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LearningPlan>()
                        .eq(LearningPlan::getUserId, userId)
                        .ne(LearningPlan::getStatus, "archived")
        );
        for (LearningPlan plan : plans) {
            List<SubPlan> subPlans = subPlanMapper.findByPlanId(plan.getId());
            for (SubPlan sp : subPlans) {
                if (moduleId != null && !moduleId.equals(sp.getModuleId())) continue;
                try {
                    Map<String, Object> spObj = objectMapper.readValue(sp.getSubPlanJson(), mapType());
                    List<Map<String, Object>> activities = castMapList(spObj.get("activities"));
                    for (Map<String, Object> act : activities) {
                        if (activityId.equals(act.get("activity_id"))) {
                            return new Container(plan, sp, spObj, act);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse sub_plan JSON: {}", sp.getId(), e);
                }
            }
        }
        return null;
    }

    private void saveSubPlan(Container ctx) {
        try {
            String json = objectMapper.writeValueAsString(ctx.subPlanObj());
            subPlanMapper.updateSubPlan(ctx.subPlan().getId(), json, ctx.subPlan().getVersion());
        } catch (Exception e) {
            log.error("Failed to save sub_plan", e);
            throw new RuntimeException("Failed to save sub_plan", e);
        }
    }

    private void writeLearningEvent(String userId, String eventType, Map<String, Object> payload) {
        LearningEvent event = new LearningEvent();
        event.setUserId(userId);
        event.setEventType(eventType);
        try {
            event.setPayloadJson(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("Failed to serialize event payload", e);
        }
        learningEventMapper.insert(event);
    }

    private void writeLearningRecords(Container ctx, Map<String, Object> activity, String status, Integer durationSeconds) {
        String userId = ctx.plan().getUserId();
        String courseId = ctx.plan().getCourseId();
        String planId = ctx.plan().getId();
        String moduleId = ctx.subPlan().getModuleId();
        String activityId = (String) activity.get("activity_id");

        List<Map<String, Object>> resources = castMapList(activity.get("resources"));
        if (resources.isEmpty()) {
            Map<String, Object> resource = castMap(activity.get("resource"));
            if (resource != null) resources = List.of(resource);
        }

        for (Map<String, Object> r : resources) {
            String resourceType = (String) r.get("resource_type");
            if (resourceType == null) continue;

            LearningRecord record = new LearningRecord();
            record.setUserId(userId);
            record.setCourseId(courseId);
            record.setPlanId(planId);
            record.setModuleId(moduleId);
            record.setActivityId(activityId);
            record.setResourcePackId((String) r.get("resource_pack_id"));
            record.setResourceType(resourceType);
            record.setStatus(status);
            record.setDurationSeconds(durationSeconds != null ? durationSeconds : 0);
            if ("completed".equals(status)) {
                record.setCompletedAt(LocalDateTime.now());
            }
            learningRecordMapper.upsert(record);
        }
    }

    private int getRetryCount(Map<String, Object> activity) {
        return activity.get("retry_count") instanceof Number n ? n.intValue() : 0;
    }

    private double getResultScore(Map<String, Object> activity) {
        Map<String, Object> result = castMap(activity.get("result"));
        if (result == null) return 0;
        return result.get("score") instanceof Number n ? n.doubleValue() : 0;
    }

    /**
     * 按主题关键词查找用户在该课程下已有的资源包（用于薄弱点推送匹配）
     */
    private List<ResourcePack> findPacksByTopic(String userId, String courseId, String topic) {
        LambdaQueryWrapper<ResourcePack> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ResourcePack::getUserId, userId)
                .eq(ResourcePack::getCourseId, courseId)
                .like(ResourcePack::getTopic, topic)
                .isNull(ResourcePack::getDeletedAt)
                .orderByDesc(ResourcePack::getCreatedAt)
                .last("LIMIT 3");
        return resourcePackMapper.selectList(wrapper);
    }

    /**
     * 在同 sub-plan 的活动中查找已匹配的 doc 资源包 ID，
     * 用于回退重学时复用已有学习材料。
     */
    private String findExistingDocPackId(List<Map<String, Object>> activities) {
        for (Map<String, Object> act : activities) {
            Map<String, Object> res = castMap(act.get("resource"));
            if (res == null) continue;
            String source = (String) res.get("source");
            String type = (String) res.get("resource_type");
            String packId = (String) res.get("resource_pack_id");
            if ("matched".equals(source) && "doc".equals(type) && packId != null && !packId.isBlank()) {
                return packId;
            }
        }
        return null;
    }

    // ==================== 查询构建 ====================

    private PlanResponse buildPlanResponse(LearningPlan plan) {
        try {
            Map<String, Object> planObj = objectMapper.readValue(plan.getPlanJson(), mapType());
            List<Map<String, Object>> modules = castMapList(planObj.get("modules"));
            List<Map<String, Object>> edges = castMapList(planObj.get("edges"));
            Map<String, Object> summary = castMap(planObj.get("summary"));

            List<PlanResponse.ModuleDto> moduleDtos = new ArrayList<>();
            for (Map<String, Object> m : modules) {
                PlanResponse.ModuleDto.ModuleDtoBuilder builder = PlanResponse.ModuleDto.builder()
                        .moduleId((String) m.get("module_id"))
                        .title((String) m.get("title"))
                        .knowledgePoints(castMapListOfStringMap(m.get("knowledge_points")))
                        .scope((String) m.get("scope"))
                        .prerequisites(castStringList(m.get("prerequisites")))
                        .estimatedHours(m.get("estimated_hours") instanceof Number n ? n.doubleValue() : null)
                        .depth((String) m.get("depth"))
                        .status((String) m.get("status"))
                        .mastery(m.get("mastery") instanceof Number n ? n.doubleValue() : null)
                        .subPlanId((String) m.get("sub_plan_id"));

                // 加载子计划
                if (m.get("sub_plan_id") != null) {
                    SubPlan sp = subPlanMapper.selectById((String) m.get("sub_plan_id"));
                    if (sp != null) {
                        builder.subPlan(parseSubPlanDto(sp));
                    }
                }
                moduleDtos.add(builder.build());
            }

            List<PlanResponse.EdgeDto> edgeDtos = edges.stream().map(e -> PlanResponse.EdgeDto.builder()
                    .from((String) e.get("from"))
                    .to((String) e.get("to"))
                    .type((String) e.get("type"))
                    .build()).toList();

            PlanResponse.SummaryDto summaryDto = summary != null ? PlanResponse.SummaryDto.builder()
                    .totalModules(summary.get("total_modules") instanceof Number n ? n.intValue() : null)
                    .coreModules(summary.get("core_modules") instanceof Number n ? n.intValue() : null)
                    .supplementaryModules(summary.get("supplementary_modules") instanceof Number n ? n.intValue() : null)
                    .totalHours(summary.get("total_hours") instanceof Number n ? n.doubleValue() : null)
                    .completionEstimate((String) summary.get("completion_estimate"))
                    .build() : null;

            return PlanResponse.builder()
                    .planId(plan.getId())
                    .version(plan.getCurrentVersion())
                    .profileVersion(plan.getProfileVersion())
                    .courseId(plan.getCourseId())
                    .status(plan.getStatus())
                    .lockMode(plan.getLockMode() != null ? plan.getLockMode() : "sequential")
                    .createdAt(plan.getCreatedAt() != null ? plan.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null)
                    .modules(moduleDtos)
                    .edges(edgeDtos)
                    .summary(summaryDto)
                    .build();
        } catch (Exception e) {
            log.error("Failed to build plan response", e);
            throw new RuntimeException("Failed to build plan response", e);
        }
    }

    private PlanResponse.SubPlanDto parseSubPlanDto(SubPlan sp) {
        try {
            Map<String, Object> spObj = objectMapper.readValue(sp.getSubPlanJson(), mapType());
            List<Map<String, Object>> activities = castMapList(spObj.get("activities"));
            List<Map<String, Object>> adjustments = castMapList(spObj.get("adjustments"));
            Map<String, Object> stats = castMap(spObj.get("stats"));
            Map<String, Object> matchSummary = castMap(spObj.get("match_summary"));

            List<PlanResponse.ActivityDto> activityDtos = activities.stream().map(a -> {
                Map<String, Object> resource = castMap(a.get("resource"));
                List<Map<String, Object>> resourcesList = castMapList(a.get("resources"));
                Map<String, Object> criteria = castMap(a.get("completionCriteria"));
                Map<String, Object> result = castMap(a.get("result"));

                List<PlanResponse.ResourceDto> resourceDtos = resourcesList != null ? resourcesList.stream()
                    .map(r -> PlanResponse.ResourceDto.builder()
                        .source((String) r.get("source"))
                        .resourcePackId((String) r.get("resource_pack_id"))
                        .resourceType((String) r.get("resource_type"))
                        .generationStatus((String) r.get("generation_status"))
                        .build())
                    .toList() : null;

                return PlanResponse.ActivityDto.builder()
                        .activityId((String) a.get("activity_id"))
                        .type((String) a.get("type"))
                        .title((String) a.get("title"))
                        .description((String) a.get("description"))
                        .requires(castStringList(a.get("requires")))
                        .resource(resource != null ? PlanResponse.ResourceDto.builder()
                                .source((String) resource.get("source"))
                                .resourcePackId((String) resource.get("resource_pack_id"))
                                .resourceType((String) resource.get("resource_type"))
                                .generationStatus((String) resource.get("generation_status"))
                                .build() : null)
                        .resources(resourceDtos)
                        .estimatedMinutes(a.get("estimated_minutes") instanceof Number n ? n.intValue() : null)
                        .order(a.get("order") instanceof Number n ? n.intValue() : null)
                        .completionCriteria(criteria != null ? PlanResponse.CompletionCriteriaDto.builder()
                                .type((String) criteria.get("type"))
                                .threshold(criteria.get("threshold") instanceof Number n ? n.doubleValue() : null)
                                .met(criteria.get("met") instanceof Boolean b ? b : false)
                                .build() : null)
                        .status((String) a.get("status"))
                        .retryCount(a.get("retry_count") instanceof Number n ? n.intValue() : 0)
                        .result(result != null ? PlanResponse.ResultDto.builder()
                                .score(result.get("score") instanceof Number n ? n.doubleValue() : null)
                                .timeSpent(result.get("time_spent") instanceof Number n ? n.doubleValue() : null)
                                .completedAt((String) result.get("completed_at"))
                                .weakTags(castStringList(result.get("weak_tags")))
                                .build() : null)
                        .build();
            }).toList();

            return PlanResponse.SubPlanDto.builder()
                    .subPlanId(sp.getId())
                    .moduleId(sp.getModuleId())
                    .version(sp.getVersion())
                    .activities(activityDtos)
                    .adjustments(adjustments)
                    .stats(stats != null ? PlanResponse.StatsDto.builder()
                            .completionPct(stats.get("completion_pct") instanceof Number n ? n.doubleValue() : null)
                            .avgQuizScore(stats.get("avg_quiz_score") instanceof Number n ? n.doubleValue() : null)
                            .totalTimeSpent(stats.get("total_time_spent") instanceof Number n ? n.doubleValue() : null)
                            .build() : null)
                    .matchSummary(parseMatchSummary(matchSummary))
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse sub_plan", e);
            return null;
        }
    }

    private PlanResponse.MatchSummaryDto parseMatchSummary(Map<String, Object> ms) {
        if (ms == null) return null;
        return PlanResponse.MatchSummaryDto.builder()
                .matchedCount(ms.get("matched_count") instanceof Number n ? n.intValue() : 0)
                .toGenerateCount(ms.get("to_generate_count") instanceof Number n ? n.intValue() : 0)
                .toGenerate(castMapListOfStringMap(ms.get("to_generate")))
                .build();
    }

    // ==================== Module/Activity 操作 ====================

    @Override
    public PlanResponse.SubPlanDto replanModule(String userId, String planId, String moduleId) {
        // 加载计划
        LearningPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new IllegalArgumentException("Plan not found: " + planId);
        }

        // 加载当前子计划
        SubPlan currentSp = subPlanMapper.findByPlanIdAndModuleId(planId, moduleId);
        if (currentSp == null) {
            throw new IllegalArgumentException("Sub-plan not found for module: " + moduleId);
        }

        try {
            Map<String, Object> spObj = objectMapper.readValue(currentSp.getSubPlanJson(), mapType());
            List<Map<String, Object>> activities = castMapList(spObj.get("activities"));

            // 将所有活动重置为 "ready" 状态（保留排序和资源）
            for (Map<String, Object> act : activities) {
                act.put("retry_count", 0);
                act.put("result", null);
                act.put("status", "ready");
                Map<String, Object> crit = castMap(act.get("completionCriteria"));
                if (crit != null) crit.put("met", false);
                Map<String, Object> res = castMap(act.get("resource"));
                if (res != null && "generated".equals(res.get("source"))) {
                    res.put("generation_status", "pending");
                }
            }
            spObj.put("activities", activities);

            // 新版本
            int newVersion = currentSp.getVersion() + 1;
            String newJson = objectMapper.writeValueAsString(spObj);
            subPlanMapper.updateSubPlan(currentSp.getId(), newJson, newVersion);
            currentSp.setVersion(newVersion);
            currentSp.setSubPlanJson(newJson);
            currentSp.setUpdatedAt(LocalDateTime.now());
            subPlanMapper.updateById(currentSp);

            log.info("Re-planned module: planId={}, moduleId={}, newVersion={}", planId, moduleId, newVersion);

            return parseSubPlanDto(currentSp);
        } catch (Exception e) {
            log.error("Failed to replan module: planId={}, moduleId={}", planId, moduleId, e);
            throw new RuntimeException("Module re-plan failed", e);
        }
    }

    @Override
    public PlanResponse.ActivityDto regenerateActivity(String userId, String planId, String moduleId, String activityId) {
        // 加载子计划
        SubPlan sp = subPlanMapper.findByPlanIdAndModuleId(planId, moduleId);
        if (sp == null) {
            throw new IllegalArgumentException("Sub-plan not found for module: " + moduleId);
        }

        try {
            Map<String, Object> spObj = objectMapper.readValue(sp.getSubPlanJson(), mapType());
            List<Map<String, Object>> activities = castMapList(spObj.get("activities"));

            // 找到目标 activity
            Map<String, Object> target = null;
            for (Map<String, Object> act : activities) {
                if (activityId.equals(act.get("activity_id"))) {
                    target = act;
                    break;
                }
            }
            if (target == null) {
                throw new IllegalArgumentException("Activity not found: " + activityId);
            }

            // 标记资源为重新生成（兼容新旧格式）
            Map<String, Object> res = castMap(target.get("resource"));
            if (res != null) {
                res.put("generation_status", "pending");
                res.put("source", "generated");
                res.put("resource_pack_id", null);
            }
            List<Map<String, Object>> resList = castMapList(target.get("resources"));
            if (resList != null) {
                for (Map<String, Object> r : resList) {
                    r.put("generation_status", "pending");
                    r.put("source", "generated");
                    r.put("resource_pack_id", null);
                }
            }
            target.put("retry_count", 0);
            target.put("result", null);
            target.put("status", "ready");
            Map<String, Object> crit = castMap(target.get("completionCriteria"));
            if (crit != null) crit.put("met", false);

            // 保存
            String newJson = objectMapper.writeValueAsString(spObj);
            subPlanMapper.updateSubPlan(sp.getId(), newJson, sp.getVersion());
            sp.setSubPlanJson(newJson);
            sp.setUpdatedAt(LocalDateTime.now());
            subPlanMapper.updateById(sp);

            log.info("Regenerated activity: planId={}, moduleId={}, activityId={}", planId, moduleId, activityId);

            // 转换为 DTO
            Map<String, Object> resource = castMap(target.get("resource"));
            List<Map<String, Object>> resourcesList = castMapList(target.get("resources"));
            Map<String, Object> criteria = castMap(target.get("completionCriteria"));

            List<PlanResponse.ResourceDto> resourceDtos = resourcesList != null ? resourcesList.stream()
                .map(r -> PlanResponse.ResourceDto.builder()
                    .source((String) r.get("source"))
                    .resourcePackId((String) r.get("resource_pack_id"))
                    .resourceType((String) r.get("resource_type"))
                    .generationStatus((String) r.get("generation_status"))
                    .build())
                .toList() : null;

            return PlanResponse.ActivityDto.builder()
                    .activityId((String) target.get("activity_id"))
                    .type((String) target.get("type"))
                    .title((String) target.get("title"))
                    .description((String) target.get("description"))
                    .requires(castStringList(target.get("requires")))
                    .resource(resource != null ? PlanResponse.ResourceDto.builder()
                            .source((String) resource.get("source"))
                            .resourcePackId((String) resource.get("resource_pack_id"))
                            .resourceType((String) resource.get("resource_type"))
                            .generationStatus((String) resource.get("generation_status"))
                            .build() : null)
                    .resources(resourceDtos)
                    .estimatedMinutes(target.get("estimated_minutes") instanceof Number n ? n.intValue() : null)
                    .status((String) target.get("status"))
                    .retryCount(target.get("retry_count") instanceof Number n ? n.intValue() : 0)
                    .build();
        } catch (Exception e) {
            log.error("Failed to regenerate activity: planId={}, activityId={}", planId, activityId, e);
            throw new RuntimeException("Activity regeneration failed", e);
        }
    }

    @Override
    public PlanResponse refreshFutureModules(String userId, String planId) {
        // 加载计划
        LearningPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new IllegalArgumentException("Plan not found: " + planId);
        }

        try {
            Map<String, Object> planObj = objectMapper.readValue(plan.getPlanJson(), mapType());
            List<Map<String, Object>> modules = castMapList(planObj.get("modules"));

            // 找到"当前"模块（第一个非 completed 的模块）
            boolean pastCurrent = false;
            for (Map<String, Object> m : modules) {
                String status = (String) m.get("status");
                if (status == null || "completed".equals(status)) {
                    // 已完成或未知状态 — 跳过
                    continue;
                }
                if (!pastCurrent) {
                    // 此为当前模块
                    pastCurrent = true;
                    continue;
                }
                // 未来模块 — 标记为需要刷新
                m.put("status", "ready");
                m.put("mastery", null);
                // 同时重置子计划（如果存在）
                String subPlanId = (String) m.get("sub_plan_id");
                if (subPlanId != null) {
                    SubPlan sp = subPlanMapper.selectById(subPlanId);
                    if (sp != null) {
                        try {
                            Map<String, Object> spObj = objectMapper.readValue(sp.getSubPlanJson(), mapType());
                            List<Map<String, Object>> activities = castMapList(spObj.get("activities"));
                            for (Map<String, Object> act : activities) {
                                act.put("status", "ready");
                                act.put("retry_count", 0);
                                act.put("result", null);
                                Map<String, Object> crit = castMap(act.get("completionCriteria"));
                                if (crit != null) crit.put("met", false);
                            }
                            spObj.put("activities", activities);
                            String newJson = objectMapper.writeValueAsString(spObj);
                            subPlanMapper.updateSubPlan(sp.getId(), newJson, sp.getVersion());
                            sp.setSubPlanJson(newJson);
                            sp.setUpdatedAt(LocalDateTime.now());
                            subPlanMapper.updateById(sp);
                        } catch (Exception e) {
                            log.warn("Failed to reset sub-plan for module: {}", m.get("module_id"), e);
                        }
                    }
                }
            }

            // 保存更新后的 plan_json
            String updatedJson = objectMapper.writeValueAsString(planObj);
            plan.setPlanJson(updatedJson);
            plan.setUpdatedAt(LocalDateTime.now());
            planMapper.updatePlan(plan.getId(), plan.getCurrentVersion(), updatedJson);

            log.info("Refreshed future modules: planId={}", planId);

            return buildPlanResponse(plan);
        } catch (Exception e) {
            log.error("Failed to refresh future modules: planId={}", planId, e);
            throw new RuntimeException("Failed to refresh future modules", e);
        }
    }

    // ==================== 锁定模式 ====================

    @Override
    @Transactional
    public PlanResponse updateLockMode(String userId, String courseId, String lockMode) {
        if (!"sequential".equals(lockMode) && !"free".equals(lockMode)) {
            throw new BusinessException("非法的锁定模式: " + lockMode);
        }
        LearningPlan plan = planMapper.findByUserIdAndCourseId(userId, courseId);
        if (plan == null) {
            throw new BusinessException("学习计划不存在");
        }

        try {
            Map<String, Object> planObj = objectMapper.readValue(plan.getPlanJson(), mapType());
            List<Map<String, Object>> modules = castMapList(planObj.get("modules"));

            List<SubPlan> subPlans = subPlanMapper.findByPlanId(plan.getId());
            Map<String, SubPlan> subPlanByModule = new LinkedHashMap<>();
            Map<String, Map<String, Object>> subPlanObjs = new LinkedHashMap<>();
            for (SubPlan sp : subPlans) {
                subPlanByModule.put(sp.getModuleId(), sp);
                subPlanObjs.put(sp.getModuleId(), objectMapper.readValue(sp.getSubPlanJson(), mapType()));
            }

            recomputeLockStates(modules, subPlanObjs, lockMode);

            plan.setPlanJson(objectMapper.writeValueAsString(planObj));

            for (Map.Entry<String, Map<String, Object>> e : subPlanObjs.entrySet()) {
                SubPlan sp = subPlanByModule.get(e.getKey());
                subPlanMapper.updateSubPlan(sp.getId(),
                        objectMapper.writeValueAsString(e.getValue()), sp.getVersion());
            }

            plan.setLockMode(lockMode);
            planMapper.updateById(plan);

            return buildPlanResponse(plan);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to update lock mode", e);
            throw new RuntimeException("Failed to update lock mode", e);
        }
    }

    /**
     * 按模式重算 activity/module 的锁定状态。
     * - free: 所有 locked -> ready
     * - sequential: 按 requires/prerequisites 重算（终态 completed/failed/in_progress/skipped 不动）
     */
    private void recomputeLockStates(List<Map<String, Object>> modules,
                                     Map<String, Map<String, Object>> subPlanObjs,
                                     String lockMode) {
        boolean free = "free".equals(lockMode);

        // ---- Activity 级 ----
        for (Map<String, Object> module : modules) {
            String moduleId = (String) module.get("module_id");
            Map<String, Object> subObj = subPlanObjs.get(moduleId);
            if (subObj == null) continue;
            List<Map<String, Object>> activities = castMapList(subObj.get("activities"));
            if (activities == null) continue;

            Map<String, String> statusSnap = new HashMap<>();
            for (Map<String, Object> a : activities) {
                statusSnap.put((String) a.get("activity_id"), (String) a.get("status"));
            }

            for (Map<String, Object> a : activities) {
                String st = (String) a.get("status");
                if ("completed".equals(st) || "in_progress".equals(st)
                        || "failed".equals(st) || "skipped".equals(st)) {
                    continue;
                }
                if (free) {
                    if ("locked".equals(st)) a.put("status", "ready");
                } else {
                    List<String> requires = castStringList(a.get("requires"));
                    boolean unlocked = true;
                    if (requires != null && !requires.isEmpty()) {
                        for (String reqId : requires) {
                            if (!"completed".equals(statusSnap.get(reqId))) {
                                unlocked = false;
                                break;
                            }
                        }
                    }
                    a.put("status", unlocked ? "ready" : "locked");
                }
            }
            subObj.put("activities", activities);
        }

        // ---- Module 级 ----
        Map<String, String> moduleStatusSnap = new HashMap<>();
        for (Map<String, Object> m : modules) {
            moduleStatusSnap.put((String) m.get("module_id"), (String) m.get("status"));
        }
        for (Map<String, Object> m : modules) {
            String st = (String) m.get("status");
            if ("completed".equals(st) || "in_progress".equals(st)) continue;
            if (free) {
                if ("locked".equals(st)) m.put("status", "ready");
            } else {
                List<String> prereq = castStringList(m.get("prerequisites"));
                boolean unlocked = true;
                if (prereq != null && !prereq.isEmpty()) {
                    for (String pid : prereq) {
                        if (!"completed".equals(moduleStatusSnap.get(pid))) {
                            unlocked = false;
                            break;
                        }
                    }
                }
                m.put("status", unlocked ? "ready" : "locked");
            }
        }
    }

    /** sequential 模式下，activity 完成后重算同 module 内锁定状态 */
    private void unlockAfterActivityComplete(Container ctx, String completedActivityId) {
        if (!"completed".equals(ctx.activity().get("status"))) return;
        if (!"sequential".equals(ctx.plan().getLockMode())) return;
        Map<String, Object> subObj = ctx.subPlanObj();
        List<Map<String, Object>> activities = castMapList(subObj.get("activities"));
        if (activities == null) return;

        Map<String, String> snap = new HashMap<>();
        for (Map<String, Object> a : activities) {
            snap.put((String) a.get("activity_id"), (String) a.get("status"));
        }
        boolean changed = false;
        for (Map<String, Object> a : activities) {
            if (!"locked".equals(a.get("status"))) continue;
            List<String> requires = castStringList(a.get("requires"));
            if (requires == null || requires.isEmpty()) continue;
            boolean allDone = true;
            for (String reqId : requires) {
                if (!"completed".equals(snap.get(reqId))) { allDone = false; break; }
            }
            if (allDone) { a.put("status", "ready"); changed = true; }
        }
        if (changed) saveSubPlan(ctx);
    }

    /** sequential 模式下，module 完成后解锁 prerequisites 依赖该 module 的 module */
    private void unlockDependentModules(Container ctx, String completedModuleId) {
        if (!"sequential".equals(ctx.plan().getLockMode())) return;
        Map<String, Object> planObj = ctx.planObj();
        List<Map<String, Object>> modules = castMapList(planObj.get("modules"));
        if (modules == null) return;
        boolean changed = false;
        for (Map<String, Object> m : modules) {
            if (!"locked".equals(m.get("status"))) continue;
            List<String> prereq = castStringList(m.get("prerequisites"));
            if (prereq == null || !prereq.contains(completedModuleId)) continue;
            boolean allDone = true;
            for (String pid : prereq) {
                boolean done = false;
                for (Map<String, Object> pm : modules) {
                    if (pid.equals(pm.get("module_id")) && "completed".equals(pm.get("status"))) {
                        done = true;
                        break;
                    }
                }
                if (!done) { allDone = false; break; }
            }
            if (allDone) { m.put("status", "ready"); changed = true; }
        }
        if (changed) {
            try {
                ctx.plan().setPlanJson(objectMapper.writeValueAsString(planObj));
                planMapper.updatePlan(ctx.plan().getId(),
                        ctx.plan().getCurrentVersion(), ctx.plan().getPlanJson());
            } catch (Exception e) {
                log.error("Failed to unlock dependent modules", e);
            }
        }
    }

    // ==================== 类型转换辅助 ====================

    /** 兼容 completionCriteria (camelCase) 和 completion_criteria (snake_case)，为空时返回空 Map */
    private Map<String, Object> getCompletionCriteria(Map<String, Object> activity) {
        Map<String, Object> c = castMap(activity.get("completionCriteria"));
        if (c != null) return c;
        c = castMap(activity.get("completion_criteria"));
        return c != null ? c : new LinkedHashMap<>();
    }

    private Map<String, Object> castMap(Object obj) {
        return obj instanceof Map ? (Map<String, Object>) obj : null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castMapList(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> (Map<String, Object>) item)
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private List<String> castStringList(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof String)
                    .map(item -> (String) item)
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> castMapListOfStringMap(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> {
                        Map<String, String> result = new LinkedHashMap<>();
                        ((Map<String, Object>) item).forEach((k, v) -> result.put(k, v != null ? v.toString() : null));
                        return result;
                    })
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    private TypeReference<Map<String, Object>> mapType() {
        return new TypeReference<>() {};
    }

    private record Container(LearningPlan plan, SubPlan subPlan, Map<String, Object> subPlanObj,
                             Map<String, Object> activity) {
        private Map<String, Object> planObj() {
            try {
                return new ObjectMapper().readValue(plan.getPlanJson(), new TypeReference<>() {});
            } catch (Exception e) {
                return new LinkedHashMap<>();
            }
        }
    }
}
