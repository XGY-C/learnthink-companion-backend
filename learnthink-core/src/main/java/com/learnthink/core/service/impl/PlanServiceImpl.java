package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.plan.ActivitySubmitRequest;
import com.learnthink.common.dto.plan.ActivitySubmitResponse;
import com.learnthink.common.dto.plan.PlanResponse;
import com.learnthink.core.domain.entity.*;
import com.learnthink.core.repository.*;
import com.learnthink.core.service.PlanService;
import com.learnthink.core.service.PushService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
    private final ObjectMapper objectMapper;
    private final PushService pushService;

    public PlanServiceImpl(LearningPlanMapper planMapper,
                           LearningPlanVersionMapper planVersionMapper,
                           SubPlanMapper subPlanMapper,
                           QuizAttemptMapper quizAttemptMapper,
                           LearningEventMapper learningEventMapper,
                           ProfileVersionMapper profileVersionMapper,
                           ResourceItemMapper resourceItemMapper,
                           ResourcePackMapper resourcePackMapper,
                           ObjectMapper objectMapper,
                           PushService pushService) {
        this.planMapper = planMapper;
        this.planVersionMapper = planVersionMapper;
        this.subPlanMapper = subPlanMapper;
        this.quizAttemptMapper = quizAttemptMapper;
        this.learningEventMapper = learningEventMapper;
        this.profileVersionMapper = profileVersionMapper;
        this.resourceItemMapper = resourceItemMapper;
        this.resourcePackMapper = resourcePackMapper;
        this.objectMapper = objectMapper;
        this.pushService = pushService;
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

        Map<String, Object> activity = ctx.activity();
        String type = (String) activity.get("type");
        Map<String, Object> criteria = castMap(activity.get("completionCriteria"));
        double threshold = criteria.get("threshold") instanceof Number n ? n.doubleValue() : QUIZ_PASS_THRESHOLD;

        if ("quiz".equals(type)) {
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
        Map<String, Object> criteria = castMap(activity.get("completionCriteria"));

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
        writeAttemptAnswers(attempt, request, weakTags);
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
        result.put("completed_at", LocalDateTime.now().toString());
        result.put("weak_tags", weakTags);
        activity.put("result", result);

        double s = score.score;

        // 达标
        if (s >= threshold) {
            criteria.put("met", true);
            activity.put("status", "completed");
            activity.put("retry_count", 0);
            saveSubPlan(ctx);
            updateModuleStatus(ctx);
            recomputeModuleMastery(ctx);

            return ActivitySubmitResponse.builder()
                    .activityId(activityId)
                    .activityCompleted(true)
                    .status("completed")
                    .score(s)
                    .weakTags(weakTags)
                    .moduleStatus(getModuleStatus(ctx, moduleId))
                    .moduleMastery(getModuleMastery(ctx, moduleId))
                    .build();
        }

        // 严重未达标 → 回退重学
        if (s < threshold * RETRY_FLOOR) {
            criteria.put("met", false);
            activity.put("status", "failed");

            ActivitySubmitResponse.AutoActionDto autoAction = insertFallbackLearnQuiz(ctx, activity, weakTags, threshold);

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
                    .build();
        }

        // 允许重试
        saveSubPlan(ctx);
        updateModuleStatus(ctx);

        return ActivitySubmitResponse.builder()
                .activityId(activityId)
                .activityCompleted(false)
                .score(s)
                .weakTags(weakTags)
                .retryCount(retryCount)
                .retryAllowed(true)
                .retriesRemaining(MAX_RETRIES - retryCount)
                .moduleStatus(getModuleStatus(ctx, moduleId))
                .build();
    }

    private ActivitySubmitResponse submitNonQuizActivity(String userId, Container ctx,
                                                          Map<String, Object> activity,
                                                          String type,
                                                          ActivitySubmitRequest request) {
        String activityId = (String) activity.get("activity_id");
        String moduleId = (String) ctx.subPlan().getModuleId();
        Map<String, Object> criteria = castMap(activity.get("completionCriteria"));
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
            result.put("completed_at", LocalDateTime.now().toString());
            activity.put("result", result);
        }

        saveSubPlan(ctx);
        updateModuleStatus(ctx);

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
        if (resource == null) return List.of();
        String packId = (String) resource.get("resource_pack_id");
        if (packId == null) return List.of();

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
            return questionList.stream()
                .map(q -> new QuestionDef(
                    (String) q.get("question_id"),
                    (String) q.get("answer"),
                    castStringList(castMap(q.get("tags")).get("mistakes")),
                    castStringList(castMap(q.get("tags")).get("knowledge"))
                ))
                .toList();
        } catch (Exception e) {
            log.error("Failed to load quiz questions for activity", e);
            return List.of();
        }
    }

    // ==================== 评分逻辑 ====================

    private ScoreResult scoreQuiz(ActivitySubmitRequest request, List<QuestionDef> questions) {
        if (request.getAnswers() == null || request.getAnswers().isEmpty()) {
            return new ScoreResult(0.0, 0, 0, List.of());
        }

        // Build lookup: questionId → QuestionDef
        Map<String, QuestionDef> lookup = new LinkedHashMap<>();
        for (QuestionDef q : questions) {
            lookup.put(q.questionId, q);
        }

        int correct = 0;
        int total = request.getAnswers().size();
        Set<String> weakTagSet = new LinkedHashSet<>();

        for (ActivitySubmitRequest.AnswerItem ans : request.getAnswers()) {
            QuestionDef q = lookup.get(ans.getQuestionId());
            if (q == null) {
                log.warn("Answer submitted for unknown question_id={}", ans.getQuestionId());
                continue;
            }
            if (q.answer != null && q.answer.equals(ans.getAnswer())) {
                correct++;
            } else {
                if (q.mistakeTags != null) weakTagSet.addAll(q.mistakeTags);
                if (q.knowledgeTags != null) weakTagSet.addAll(q.knowledgeTags);
            }
        }

        double score = total > 0 ? (double) correct / total : 0;
        List<String> weakTags = new ArrayList<>(weakTagSet);
        log.info("Quiz scored: {}/{} correct, score={}, weakTags={}", correct, total, score, weakTags);
        return new ScoreResult(score, correct, total, weakTags);
    }

    private void writeAttemptAnswers(QuizAttempt attempt, ActivitySubmitRequest request, List<String> weakTags) {
        try {
            attempt.setAnswersJson(objectMapper.writeValueAsString(request.getAnswers()));
            attempt.setWeakTags(objectMapper.writeValueAsString(weakTags));
            attempt.setDurationSeconds(request.getDurationSeconds());
        } catch (Exception e) {
            log.error("Failed to serialize quiz answers", e);
        }
    }

    private record QuestionDef(String questionId, String answer, List<String> mistakeTags, List<String> knowledgeTags) {}
    private record ScoreResult(double score, int correct, int total, List<String> weakTags) {}

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
        learnActivity.put("resource", hasExistingDoc
            ? Map.of("source", "matched", "resource_pack_id", fallbackPackId, "resource_type", "doc", "generation_status", (String) null)
            : Map.of("source", "generated", "resource_pack_id", (String) null, "resource_type", "doc", "generation_status", "pending"));
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
        quizActivity.put("resource", Map.of("source", "generated", "resource_pack_id", (String) null, "resource_type", "quiz", "generation_status", "pending"));
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
        adj.put("at", LocalDateTime.now().toString());
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

        // 模块刚刚变成 completed → 检查下一模块资源并推送
        String newStatus = getModuleStatus(ctx, moduleId);
        if ("completed".equals(newStatus) && !"completed".equals(oldStatus)) {
            tryPushPathNext(ctx, moduleId);
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
        List<LearningPlan> plans = planMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LearningPlan>()
                        .eq(LearningPlan::getUserId, userId)
                        .ne(LearningPlan::getStatus, "archived")
        );
        for (LearningPlan plan : plans) {
            List<SubPlan> subPlans = subPlanMapper.findByPlanId(plan.getId());
            for (SubPlan sp : subPlans) {
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
                    .createdAt(plan.getCreatedAt() != null ? plan.getCreatedAt().toString() : null)
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

    // ==================== 类型转换辅助 ====================

    @SuppressWarnings("unchecked")
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
