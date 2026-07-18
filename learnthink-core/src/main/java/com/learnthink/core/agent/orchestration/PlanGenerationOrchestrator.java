package com.learnthink.core.agent.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.config.PromptLoader;
import com.learnthink.core.domain.entity.*;
import com.learnthink.core.repository.*;
import com.learnthink.core.service.TaskPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 学习计划生成编排器 — 四阶段流水线
 * <p>
 * Phase 1: 大计划生成 (LLM → module DAG)
 * Phase 2: 资源匹配 (对每个 module 检索已有 resource_pack)
 * Phase 3: 子计划并行生成 (LLM → 每个 module 的 activity 序列)
 * Phase 4: 缺口资源生成 (排队进 ResourceGenerationGraph)
 */
@Service
public class PlanGenerationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PlanGenerationOrchestrator.class);

    private final ChatClient reasoningChatClient;
    private final ChatClient generationChatClient;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;
    private final TaskPersistenceService taskPersistence;
    private final TaskEventBroadcaster broadcaster;
    private final TaskOrchestrator taskOrchestrator;

    private final ProfileVersionMapper profileVersionMapper;
    private final CourseKnowledgePointMapper kpMapper;
    private final LearningPlanMapper planMapper;
    private final LearningPlanVersionMapper planVersionMapper;
    private final SubPlanMapper subPlanMapper;
    private final ResourcePackMapper resourcePackMapper;
    private final ResourceItemMapper resourceItemMapper;
    private final CourseMapper courseMapper;

    private final ExecutorService subPlanPool = Executors.newFixedThreadPool(6);

    public PlanGenerationOrchestrator(
            @Qualifier("reasoningChatClientBuilder") ChatClient.Builder reasoningBuilder,
            @Qualifier("generationChatClientBuilder") ChatClient.Builder generationBuilder,
            PromptLoader promptLoader,
            ObjectMapper objectMapper,
            TaskPersistenceService taskPersistence,
            TaskEventBroadcaster broadcaster,
            TaskOrchestrator taskOrchestrator,
            ProfileVersionMapper profileVersionMapper,
            CourseKnowledgePointMapper kpMapper,
            LearningPlanMapper planMapper,
            LearningPlanVersionMapper planVersionMapper,
            SubPlanMapper subPlanMapper,
            ResourcePackMapper resourcePackMapper,
            ResourceItemMapper resourceItemMapper,
            CourseMapper courseMapper) {
        this.reasoningChatClient = reasoningBuilder
                .defaultOptions(OpenAiChatOptions.builder().temperature(0.2).build())
                .build();
        this.generationChatClient = generationBuilder
                .defaultOptions(OpenAiChatOptions.builder().temperature(0.3).build())
                .build();
        this.promptLoader = promptLoader;
        this.objectMapper = objectMapper;
        this.taskPersistence = taskPersistence;
        this.broadcaster = broadcaster;
        this.taskOrchestrator = taskOrchestrator;
        this.profileVersionMapper = profileVersionMapper;
        this.kpMapper = kpMapper;
        this.planMapper = planMapper;
        this.planVersionMapper = planVersionMapper;
        this.subPlanMapper = subPlanMapper;
        this.resourcePackMapper = resourcePackMapper;
        this.resourceItemMapper = resourceItemMapper;
        this.courseMapper = courseMapper;
    }

    @PreDestroy
    public void destroy() {
        subPlanPool.shutdown();
    }

    /**
     * 查询用户+课程下正在进行的 plan_generate 任务 ID（无则返回 null）
     */
    public String findActivePlanTaskId(String userId, String courseId) {
        List<Task> activeTasks = taskPersistence.findActivePlanTasks(userId, courseId);
        return activeTasks.isEmpty() ? null : activeTasks.get(0).getId();
    }

    /**
     * 取消用户+课程下所有进行中的 plan_generate 任务
     */
    public void cancelActivePlanTasks(String userId, String courseId) {
        List<Task> activeTasks = taskPersistence.findActivePlanTasks(userId, courseId);
        for (Task t : activeTasks) {
            taskPersistence.failTask(t.getId(), "CANCELLED_BY_USER", "用户选择重新规划");
            log.info("Cancelled existing plan task: taskId={} for user={} course={}", t.getId(), userId, courseId);
        }
    }

    /**
     * 异步启动学习计划生成任务
     * @param force 强制重新生成（会先取消已有进行中任务）
     */
    public String startGeneration(String userId, String courseId, Integer profileVersion, boolean force,
                                   String requirementText) {
        if (force) {
            cancelActivePlanTasks(userId, courseId);
        }

        String taskId = UUID.randomUUID().toString();

        // 1. 创建任务记录
        taskPersistence.createTask(taskId, userId, courseId, "plan_generate",
                "学习计划生成", "[\"plan\"]", profileVersion, null);
        taskPersistence.updateTaskStage(taskId, "INIT", 0, "PENDING");
        broadcaster.taskAccepted(taskId, java.time.Instant.now());

        // 2. 异步执行
        CompletableFuture.runAsync(() -> executeGeneration(taskId, userId, courseId, profileVersion, requirementText));

        return taskId;
    }

    /**
     * 预览大计划 — 同步调用，返回 modules + edges + summary
     * v3.1: 生成后立即落库（status=pending_decision），切换会话不丢方案
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> previewPlan(String userId, String courseId, Integer profileVersion,
                                            String requirementText, String chatId) {
        // 加载上下文
        ProfileVersion profile = loadProfile(userId, courseId, profileVersion);
        List<CourseKnowledgePoint> kpTree = kpMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CourseKnowledgePoint>()
                        .eq(CourseKnowledgePoint::getCourseId, courseId)
                        .orderByAsc(CourseKnowledgePoint::getSortOrder));

        String profileSummary = profile != null ? profile.getDisplayJson() : "{}";
        String courseKpTreeJson = buildKpTreeJson(kpTree);

        if (kpTree.isEmpty()) {
            Course course = courseMapper.selectById(courseId);
            String courseName = course != null ? course.getName() : courseId;
            courseKpTreeJson = "（该课程暂无知识图谱数据。请根据课程名称「" + courseName
                    + "」和画像信息，自行推断合理的章节结构和知识点划分。）";
        }

        Map<String, Object> bigPlan = generateBigPlan(courseKpTreeJson, profileSummary, requirementText);

        // v3.1: 立即落库（status=pending_decision），切换会话不丢方案
        // 仅当无已有计划 或 已有计划为 pending_decision 时才落库，
        // 避免覆盖用户已确认/已完成的有效计划
        try {
            LearningPlan existing = planMapper.findByUserIdAndCourseId(userId, courseId);
            if (existing == null || "pending_decision".equals(existing.getStatus())) {
                String planJson = objectMapper.writeValueAsString(bigPlan);
                String planId = saveLearningPlanWithStatus(userId, courseId, profileVersion,
                        planJson, "pending_decision", chatId);
                bigPlan.put("plan_id", planId);
            } else {
                log.info("Skipping preview persist: existing plan has status={}", existing.getStatus());
            }
        } catch (Exception e) {
            log.error("Failed to persist plan draft on preview", e);
            // 不阻断返回 — 前端仍可展示 PlanEditor，只是切换会话后需重新生成
        }

        return bigPlan;
    }

    /**
     * 更新 pending_decision 状态的计划草稿 — 用户编辑后保存
     * @return 更新后的 plan JSON（含 plan_id）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> updatePlanDraft(String userId, String planId, String courseId,
                                                Integer profileVersion, String planJson, String chatId,
                                                String requirementText) {
        LearningPlan existing = null;
        if (planId != null && !planId.isBlank()) {
            existing = planMapper.selectById(planId);
        }

        if (existing != null) {
            if (!"pending_decision".equals(existing.getStatus())) {
                throw new IllegalStateException("计划状态不允许编辑: " + existing.getStatus());
            }
            existing.setPlanJson(planJson);
            existing.setUpdatedAt(LocalDateTime.now());
            planMapper.updateById(existing);
            try {
                Map<String, Object> result = objectMapper.readValue(planJson, LinkedHashMap.class);
                result.put("plan_id", existing.getId());
                result.put("status", existing.getStatus());
                return result;
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to parse plan JSON", e);
            }
        } else {
            // 首次保存：创建新记录
            String newPlanId = saveLearningPlanWithStatus(userId, courseId, profileVersion,
                    planJson, "pending_decision", chatId);
            try {
                Map<String, Object> result = objectMapper.readValue(planJson, LinkedHashMap.class);
                result.put("plan_id", newPlanId);
                result.put("status", "pending_decision");
                return result;
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to parse plan JSON", e);
            }
        }
    }

    /**
     * 确认大计划并异步生成子计划 — 跳过 Phase 1（大计划 LLM），使用用户编辑后的 plan
     * @return taskId
     */
    public String confirmPlanAndGenerate(String userId, String courseId, Integer profileVersion,
                                          String planJson, String requirementText, String chatId) {
        String taskId = UUID.randomUUID().toString();

        taskPersistence.createTask(taskId, userId, courseId, "plan_generate",
                "学习计划生成", "[\"plan\"]", profileVersion, chatId);
        taskPersistence.updateTaskStage(taskId, "INIT", 0, "PENDING");
        broadcaster.taskAccepted(taskId, java.time.Instant.now());

        // 异步执行：直接落库后跑 Phase 2-4
        CompletableFuture.runAsync(() -> {
            try {
                taskPersistence.updateTaskStage(taskId, "PLANNING", 0, "RUNNING");
                advance(taskId, "PLANNING", 5, "正在应用用户确认的学习方案...");

                @SuppressWarnings("unchecked")
                Map<String, Object> bigPlan = objectMapper.readValue(planJson, LinkedHashMap.class);
                List<Map<String, Object>> modules = castMapList(bigPlan.get("modules"));
                List<Map<String, Object>> edges = castMapList(bigPlan.get("edges"));
                Map<String, Object> summary = castMap(bigPlan.get("summary"));

                log.info("Confirm plan: {} modules, {} edges", modules.size(), edges.size());

                if (modules.isEmpty()) {
                    taskPersistence.failTask(taskId, "EMPTY_PLAN",
                            "学习计划生成失败：用户确认的方案中没有模块。");
                    broadcaster.taskFailed(taskId, "EMPTY_PLAN", "学习计划为空", true);
                    return;
                }

                // 落库 — 若 pending_decision 草稿已存在则更新为 decided，否则新建
                String savedPlanJson = objectMapper.writeValueAsString(bigPlan);
                String planId;
                LearningPlan existing = planMapper.findByUserIdAndCourseId(userId, courseId);
                if (existing != null && "pending_decision".equals(existing.getStatus())) {
                    planId = existing.getId();
                    existing.setPlanJson(savedPlanJson);
                    existing.setStatus("decided");
                    existing.setUpdatedAt(LocalDateTime.now());
                    planMapper.updateById(existing);
                    log.info("Plan draft upgraded to decided: planId={}", planId);
                } else {
                    planId = saveLearningPlanWithStatus(userId, courseId, profileVersion,
                            savedPlanJson, "decided", chatId);
                }
                bigPlan.put("plan_id", planId);
                savedPlanJson = objectMapper.writeValueAsString(bigPlan);

                advance(taskId, "PLANNING", 35, "大计划已就绪 — " + modules.size() + " 个学习模块");

                // Phase 2-4
                runPostPlanPhases(taskId, userId, courseId, profileVersion, planId,
                        bigPlan, modules, edges, summary, savedPlanJson);

            } catch (Exception e) {
                log.error("Confirm plan generation failed: taskId={}", taskId, e);
                taskPersistence.failTask(taskId, "PLAN_GENERATION_ERROR", e.getMessage());
                broadcaster.taskFailed(taskId, "PLAN_GENERATION_ERROR", e.getMessage(), false);
            }
        });

        return taskId;
    }

    /**
     * 执行学习计划生成的主方法
     * 该方法负责整个学习计划的生成流程，包括加载上下文、生成大计划、资源匹配、子计划生成和缺口资源生成等阶段
     *
     * @param taskId 任务ID
     * @param userId 用户ID
     * @param courseId 课程ID
     * @param profileVersion 画像版本号
     */
    private void executeGeneration(String taskId, String userId, String courseId, Integer profileVersion,
                                     String requirementText) {
        try {
            // 更新任务状态为运行中，并设置初始进度
            taskPersistence.updateTaskStage(taskId, "PLANNING", 0, "RUNNING");
            // 推进任务进度，并显示加载信息
            advance(taskId, "PLANNING", 5, "正在加载画像与课程知识图谱...");

            // === Phase 0: 加载上下文 ===
            // 加载用户画像信息
            ProfileVersion profile = loadProfile(userId, courseId, profileVersion);
            // 从数据库中查询课程的知识点树，按排序顺序排列
            List<CourseKnowledgePoint> kpTree = kpMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CourseKnowledgePoint>()
                            .eq(CourseKnowledgePoint::getCourseId, courseId)
                            .orderByAsc(CourseKnowledgePoint::getSortOrder));

            // 获取画像摘要JSON，如果为空则使用空对象
            String profileSummary = profile != null ? profile.getDisplayJson() : "{}";
            // TODO 构建知识点树JSON
            String courseKpTreeJson = buildKpTreeJson(kpTree);

            // KP 数据缺失时降级运行：告知 LLM 基于课程名称和画像自行推断知识结构
            if (kpTree.isEmpty()) {
                log.warn("Course {} has 0 knowledge points — plan will be generated without KP tree guidance", courseId);
            // 查询课程信息
                Course course = courseMapper.selectById(courseId);
                String courseName = course != null ? course.getName() : courseId;
            // 构建降级提示信息
                courseKpTreeJson = "（该课程暂无知识图谱数据。请根据课程名称「" + courseName
                        + "」和画像信息，自行推断合理的章节结构和知识点划分。）";
            }

        // 推进任务进度，显示分析课程结构信息
            advance(taskId, "PLANNING", 10, "正在分析课程结构，生成学习大计划...");

            // === Phase 1: 大计划生成 ===
        // 生成大计划，包含模块、边和摘要信息
            Map<String, Object> bigPlan = generateBigPlan(courseKpTreeJson, profileSummary, requirementText);
        // 提取模块列表
            List<Map<String, Object>> modules = castMapList(bigPlan.get("modules"));
        // 提取边列表
            List<Map<String, Object>> edges = castMapList(bigPlan.get("edges"));
        // 提取摘要信息
            Map<String, Object> summary = castMap(bigPlan.get("summary"));

        // 记录生成的大计划信息
            log.info("Big plan generated: {} modules, {} edges", modules.size(), edges.size());

        // 检查模块列表是否为空
            if (modules.isEmpty()) {
                log.error("Big plan LLM returned 0 modules. Raw plan JSON: {}",
                        objectMapper.writeValueAsString(bigPlan).substring(0, Math.min(500, objectMapper.writeValueAsString(bigPlan).length())));
            // 标记任务失败
                taskPersistence.failTask(taskId, "EMPTY_PLAN",
                        "学习计划生成失败：LLM 返回了空的模块列表（0 modules）。请检查 course_kp_tree 和 profile_summary 数据是否完整。");
                broadcaster.taskFailed(taskId, "EMPTY_PLAN",
                        "学习计划生成失败：课程知识图谱或画像数据可能不完整", true);
                return;
            }

            // 保存大计划到数据库
            String planJson = objectMapper.writeValueAsString(bigPlan);
            String planId = saveLearningPlan(userId, courseId, profileVersion, planJson);
            // 将 plan_id 回注到 JSON 中，供后续 Phase 3 子计划生成使用
            bigPlan.put("plan_id", planId);
            planJson = objectMapper.writeValueAsString(bigPlan);

        // 推进任务进度，显示大计划生成完成信息
            advance(taskId, "PLANNING", 35, "大计划已生成 — " + modules.size() + " 个学习模块");

            // Phase 2-4
            runPostPlanPhases(taskId, userId, courseId, profileVersion, planId,
                    bigPlan, modules, edges, summary, planJson);

        } catch (Exception e) {
        // 记录计划生成失败信息
            log.error("Plan generation failed: taskId={}", taskId, e);
        // 标记任务失败
            taskPersistence.failTask(taskId, "PLAN_GENERATION_ERROR", e.getMessage());
        // 广播任务失败事件
            broadcaster.taskFailed(taskId, "PLAN_GENERATION_ERROR", e.getMessage(), false);
        }
    }

    // ==================== Phase 1: 大计划 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> generateBigPlan(String courseKpTreeJson, String profileSummary,
                                                  String userRequirement) {
        String prompt = promptLoader.get("plan/big_plan")
                .replace("{course_kp_tree}", courseKpTreeJson)
                .replace("{profile_summary}", profileSummary)
                .replace("{user_requirement}", userRequirement != null ? userRequirement : "（用户未提供额外需求描述，请根据画像和课程结构自行规划）");

        String content = reasoningChatClient.prompt()
                .messages(
                        new org.springframework.ai.chat.messages.SystemMessage(prompt),
                        new org.springframework.ai.chat.messages.UserMessage("请根据课程知识图谱和画像信息，生成一份个性化的学习大计划。"))
                .call()
                .content();

        log.info("Big plan LLM response length: {} chars", content != null ? content.length() : 0);

        try {
            String json = extractJson(content);
            log.info("Extracted big plan JSON length: {} chars", json.length());
            return objectMapper.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            log.error("Failed to parse big plan LLM response. Raw content (first 500 chars): {}",
                    content != null ? content.substring(0, Math.min(500, content.length())) : "null");
            throw new RuntimeException("大计划生成失败：LLM 输出解析错误", e);
        }
    }

    // ==================== Phase 2-4: 共享方法 ====================

    /**
     * 执行 Phase 2（资源匹配）+ Phase 3（子计划）+ Phase 4（缺口资源），
     * 同时供 executeGeneration（完整流程）和 confirmPlanAndGenerate（跳过LLM）调用
     */
    @SuppressWarnings("unchecked")
    private void runPostPlanPhases(String taskId, String userId, String courseId, Integer profileVersion,
                                    String planId, Map<String, Object> bigPlan,
                                    List<Map<String, Object>> modules, List<Map<String, Object>> edges,
                                    Map<String, Object> summary, String planJson) {
        try {
            // === Phase 2: 资源匹配 ===
            advance(taskId, "MATCHING", 40, "正在从已有资源中匹配高质量内容...");
            List<Map<String, Object>> matchResults = matchResources(modules, courseId);
            List<Map<String, Object>> matchSummaries = matchResults.stream()
                    .map(r -> castMap(r.get("match_summary")))
                    .collect(Collectors.toList());

            int totalMatched = matchSummaries.stream()
                    .mapToInt(m -> m.get("matched_count") instanceof Number n ? n.intValue() : 0)
                    .sum();
            int totalToGenerate = matchSummaries.stream()
                    .mapToInt(m -> m.get("to_generate_count") instanceof Number n ? n.intValue() : 0)
                    .sum();

            advance(taskId, "MATCHING", 50, "资源匹配完成 — 已匹配 " + totalMatched + " 份，需生成 " + totalToGenerate + " 份");
            log.info("Phase 2 resource matching complete: {} modules scanned, {} matched, {} to generate",
                    modules.size(), totalMatched, totalToGenerate);

            // === Phase 3: 子计划并行生成 ===
            subPlanMapper.deleteByPlanId(planId);

            advance(taskId, "SUBPLANNING", 55, "正在为每个模块规划详细学习活动...");
            log.info("Phase 3 sub-plan generation started: {} modules to process", modules.size());

            ProfileVersion profile = loadProfile(userId, courseId, profileVersion);
            List<CourseKnowledgePoint> kpTree = kpMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CourseKnowledgePoint>()
                            .eq(CourseKnowledgePoint::getCourseId, courseId)
                            .orderByAsc(CourseKnowledgePoint::getSortOrder));

            List<Future<SubPlanResult>> futures = new ArrayList<>();
            String finalPlanJson = planJson;
            for (int i = 0; i < modules.size(); i++) {
                int idx = i;
                futures.add(subPlanPool.submit(() -> {
                    Map<String, Object> module = modules.get(idx);
                    Map<String, Object> matchResult = idx < matchResults.size() ? matchResults.get(idx) : new LinkedHashMap<>();
                    List<CourseKnowledgePoint> moduleKps = extractModuleKps(kpTree, module);
                    return generateSubPlan(module, moduleKps, matchResult, profile,
                            kpTree, finalPlanJson, userId, courseId, profileVersion);
                }));
            }

            List<SubPlanResult> subPlanResults = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    SubPlanResult r = futures.get(i).get(120, TimeUnit.SECONDS);
                    subPlanResults.add(r);
                    log.info("Sub-plan {} succeeded: moduleIdx={}, activities={}, toGenerate={}",
                            i, i, r.activities().size(), r.toGenerateCount());
                    advance(taskId, "SUBPLANNING", 55 + (i * 25 / futures.size()),
                            "子计划生成中 (" + (i + 1) + "/" + futures.size() + ")");
                } catch (Exception e) {
                    log.error("Sub-plan generation failed for module {}", i, e);
                    subPlanResults.add(new SubPlanResult(null, new ArrayList<>(), 0));
                }
            }

            // 保存子计划并回填 sub_plan_id
            for (int i = 0; i < modules.size() && i < subPlanResults.size(); i++) {
                SubPlanResult r = subPlanResults.get(i);
                if (r.subPlanId() != null) {
                    Map<String, Object> m = modules.get(i);
                    m.put("sub_plan_id", r.subPlanId());
                }
            }

            String updatedPlanJson = objectMapper.writeValueAsString(bigPlan);
            planMapper.updatePlanJson(planId, updatedPlanJson);

            advance(taskId, "SUBPLANNING", 80, "子计划全部就绪");
            long succeededSubPlans = subPlanResults.stream().filter(r -> r.subPlanId() != null).count();
            long failedSubPlans = subPlanResults.size() - succeededSubPlans;
            int totalActivities = subPlanResults.stream().mapToInt(r -> r.activities().size()).sum();
            log.info("Phase 3 sub-plan generation complete: {} succeeded, {} failed, {} total activities",
                    succeededSubPlans, failedSubPlans, totalActivities);

            // === Phase 4: 缺口资源生成（排队）===
            int genCount = 0;
            for (SubPlanResult r : subPlanResults) {
                genCount += r.toGenerateCount();
            }
            List<Map<String, Object>> gapTaskInfos = new ArrayList<>();
            List<String> gapTaskIds = new ArrayList<>();
            if (genCount > 0) {
                advance(taskId, "GENERATING", 85, "缺口资源生成就绪 (" + genCount + " 份待生成)");
                gapTaskInfos = queueGapResourceGeneration(taskId, userId, profileVersion, courseId, planId, modules, subPlanResults);
                if (!gapTaskInfos.isEmpty()) {
                    gapTaskIds = gapTaskInfos.stream()
                            .map(t -> (String) t.get("task_id"))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                    broadcaster.broadcastEvent(taskId, "plan.gap_tasks", Map.of("tasks", gapTaskInfos));
                }
            }

            // 等待所有缺口资源生成完毕，才标记计划任务完成
            if (!gapTaskIds.isEmpty()) {
                waitForGapTasks(taskId, gapTaskIds);
            }

            // === 完成 ===
            advance(taskId, "COMPLETED", 100, "学习计划生成完成");
            taskPersistence.updateTaskStage(taskId, "COMPLETED", 100, "SUCCEEDED");
            planMapper.updateStatus(planId, "completed");
            broadcaster.taskDone(taskId, "SUCCEEDED", planId, modules.size(), Set.of());

            broadcaster.broadcastEvent(taskId, "plan.done", Map.of(
                    "plan_id", planId,
                    "modules", modules.size(),
                    "matched_resources", totalMatched,
                    "generated_resources", genCount
            ));

            log.info("Plan generation completed: taskId={}, planId={}, modules={}, matched={}, toGenerate={}",
                    taskId, planId, modules.size(), totalMatched, genCount);

        } catch (Exception e) {
            log.error("Post-plan phases failed: taskId={}", taskId, e);
            taskPersistence.failTask(taskId, "PLAN_GENERATION_ERROR", e.getMessage());
            broadcaster.taskFailed(taskId, "PLAN_GENERATION_ERROR", e.getMessage(), false);
        }
    }

    // ==================== Phase 2: 资源匹配 ====================

    private List<Map<String, Object>> matchResources(List<Map<String, Object>> modules, String courseId) {
        List<Map<String, Object>> results = new ArrayList<>();

        for (Map<String, Object> module : modules) {
            List<Map<String, String>> kps = castMapListOfStringMap(module.get("knowledge_points"));
            Set<String> kpNames = kps.stream()
                    .map(kp -> kp.get("name"))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            // 查询该课程下所有 resource_packs
            List<ResourcePack> packs = resourcePackMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ResourcePack>()
                            .eq(ResourcePack::getCourseId, courseId)
                            .orderByDesc(ResourcePack::getCreatedAt));

            List<Map<String, Object>> matchedBest = new ArrayList<>();
            List<Map<String, Object>> matchedStale = new ArrayList<>();
            List<Map<String, Object>> matchedPartial = new ArrayList<>();
            Set<String> matchedTypes = new HashSet<>();
            Set<String> partialTypes = new HashSet<>();

            for (ResourcePack pack : packs) {
                // Step 1: KP 语义匹配 — pack topic 必须包含 KP 名称（子串匹配）。
                // 只检查 packTopic.contains(kp)，避免逆向匹配（kp.contains(packTopic)）
                // 导致短 topic 误匹配长 KP 名字（如 topic "数据" 匹配 KP "数据结构与算法"）。
                // 极短 KP（< 2 字符）跳过，避免 "集合" 等通用词错误匹配。
                String packTopic = pack.getTopic() != null ? pack.getTopic() : "";
                long kpMatchCount = kpNames.stream()
                    .filter(kp -> kp != null && kp.length() >= 2)
                    .filter(packTopic::contains)
                    .count();

                if (kpMatchCount == 0) continue;

                // Step 2: 检查 resource_items 的状态
                List<ResourceItem> items = resourceItemMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ResourceItem>()
                                .eq(ResourceItem::getPackId, pack.getId()));

                boolean allApproved = items.stream().allMatch(
                        i -> "approved".equals(i.getReviewStatus()));
                boolean hasLowConfidence = items.stream().anyMatch(
                        i -> "low".equals(i.getConfidence()));

                // Step 3-5: 判定匹配等级
                boolean exactMatch = kpMatchCount >= kpNames.size() * 0.8;
                boolean partialMatch = kpMatchCount > 0;

                if (exactMatch && !hasLowConfidence) {
                    // 收集已匹配的资源类型
                    for (ResourceItem item : items) {
                        String type = item.getType();
                        matchedTypes.add(type);
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("resource_pack_id", pack.getId());
                        entry.put("resource_type", type);
                        entry.put("rating", allApproved ? 1.0 : 0.8);
                        matchedBest.add(entry);
                    }
                } else if (exactMatch && hasLowConfidence) {
                    for (ResourceItem item : items) {
                        matchedTypes.add(item.getType());
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("resource_pack_id", pack.getId());
                        entry.put("resource_type", item.getType());
                        entry.put("rating", 0.8);
                        matchedStale.add(entry);
                    }
                } else if (partialMatch) {
                    for (ResourceItem item : items) {
                        partialTypes.add(item.getType());
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("resource_pack_id", pack.getId());
                        entry.put("resource_type", item.getType());
                        entry.put("rating", 0.5);
                        matchedPartial.add(entry);
                    }
                }
            }

            // 计算需要生成的资源类型（根据 depth 决定）
            String depth = (String) module.get("depth");
            String scope = (String) module.get("scope");
            List<String> requiredTypes = getRequiredTypes(depth, scope);
            List<Map<String, Object>> toGenerate = new ArrayList<>();

            Set<String> allMatched = new HashSet<>();
            allMatched.addAll(matchedTypes);
            allMatched.addAll(partialTypes);

            for (String type : requiredTypes) {
                if (!allMatched.contains(type)) {
                    Map<String, Object> gen = new LinkedHashMap<>();
                    gen.put("type", type);
                    gen.put("topic", module.get("title"));
                    gen.put("reason", "无匹配的" + type + "类型资源");
                    toGenerate.add(gen);
                }
            }

            Map<String, Object> matchSummary = new LinkedHashMap<>();
            matchSummary.put("matched_count", allMatched.size());
            matchSummary.put("to_generate_count", toGenerate.size());
            matchSummary.put("to_generate", toGenerate);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("module_id", module.get("module_id"));
            result.put("matches", Map.of(
                    "matched_best", matchedBest,
                    "matched_stale", matchedStale,
                    "matched_partial", matchedPartial,
                    "to_generate", toGenerate
            ));
            result.put("match_summary", matchSummary);

            results.add(result);
        }

        return results;
    }

    private List<String> getRequiredTypes(String depth, String scope) {
        if ("basic".equals(depth) && !"core_curriculum".equals(scope)) {
            return List.of("doc", "quiz");
        }
        if ("deep".equals(depth)) {
            return List.of("doc", "mindmap", "quiz", "code");
        }
        return List.of("doc", "quiz");
    }

    // ==================== Phase 3: 子计划 ====================

    private SubPlanResult generateSubPlan(Map<String, Object> module,
                                           List<CourseKnowledgePoint> moduleKps,
                                           Map<String, Object> matchResult,
                                           ProfileVersion profile,
                                           List<CourseKnowledgePoint> kpTree,
                                           String planJson,
                                           String userId,
                                           String courseId,
                                           Integer profileVersion) {
        try {
            String moduleTitle = (String) module.get("title");
            String moduleDescription = (String) module.get("description");
            if (moduleDescription == null || moduleDescription.isBlank()) {
                // 兜底：无 description 时回退到 KP 拼接
                moduleDescription = moduleKps.stream()
                        .map(kp -> kp.getName() + "：" + (kp.getDescription() != null ? kp.getDescription() : ""))
                        .collect(Collectors.joining("\n"));
            }

            // 传入完整匹配结果（含 matched_best/stale/partial 的实际 resource_pack_id），而非仅 summary
            String matchContextJson = objectMapper.writeValueAsString(matchResult);

            // 提取必须覆盖的资源类型（显式字段，不埋在 JSON 里让 LLM 自己找）
            String depth = (String) module.getOrDefault("depth", "standard");
            String scope = (String) module.getOrDefault("scope", "core_curriculum");
            List<String> canonicalRequired = getRequiredTypes(depth, scope);
            String requiredTypesText = buildRequiredTypesText(matchResult, canonicalRequired);

            // 构建 plan_overview
            Map<String, Object> planObj = objectMapper.readValue(planJson, mapType());
            String planOverview = buildPlanOverview(planObj);

            // 从画像提取认知风格和错误模式
            String profileJson = profile != null ? profile.getDisplayJson() : "{}";
            Map<String, Object> profileMap = safeParseJson(profileJson);

            String prompt = promptLoader.get("plan/sub_plan")
                    .replace("{plan_overview}", planOverview)
                    .replace("{module_title}", moduleTitle)
                    .replace("{module_description}", moduleDescription)
                    .replace("{module_depth}", depth)
                    .replace("{module_scope}", scope)
                    .replace("{module_prerequisites}", String.valueOf(module.getOrDefault("prerequisites", List.of())))
                    .replace("{match_summary}", matchContextJson)
                    .replace("{required_types}", requiredTypesText)
                    .replace("{cognitive_style}", extractNestedField(profileMap, "cognitive_style"))
                    .replace("{error_patterns}", extractNestedField(profileMap, "error_pattern"))
                    .replace("{minutes_per_day}", extractNestedField(profileMap, "minutes_per_day", "30"))
                    .replace("{days_per_week}", extractNestedField(profileMap, "days_per_week", "5"))
                    .replace("{learning_goal}", extractNestedField(profileMap, "learning_goal"));

            String content = generationChatClient.prompt()
                    .messages(
                            new org.springframework.ai.chat.messages.SystemMessage(prompt),
                            new org.springframework.ai.chat.messages.UserMessage(
                                    "请为「" + moduleTitle + "」module 生成学习活动序列。"))
                    .call()
                    .content();

            String json = extractJson(content);
            Map<String, Object> subPlanObj = objectMapper.readValue(json, mapType());
            List<Map<String, Object>> activities = castMapList(subPlanObj.get("activities"));

            // 为每个 activity 分配顺序号
            for (int i = 0; i < activities.size(); i++) {
                activities.get(i).put("order", i + 1);
                if (activities.get(i).get("status") == null) {
                    boolean locked = false;
                    List<String> requires = castStringList(activities.get(i).get("requires"));
                    if (requires != null && !requires.isEmpty()) {
                        locked = true; // 需要前置 — 初始为 locked，pre-requisite 完成后变为 ready
                    }
                    activities.get(i).put("status", locked ? "locked" : "ready");
                }
                if (activities.get(i).get("retry_count") == null) {
                    activities.get(i).put("retry_count", 0);
                }
            }

            subPlanObj.put("activities", activities);

            // 后处理：遍历 activities 的 resources[]，填充技术字段 + 回填 matched resource_pack_id
            Map<String, String> typeToBestPackId = buildTypeToPackIdMap(matchResult);
            for (Map<String, Object> act : activities) {
                List<Map<String, Object>> resources = castMapList(act.get("resources"));
                if (resources == null) continue;
                for (Map<String, Object> res : resources) {
                    String source = (String) res.get("source");
                    if ("generated".equals(source)) {
                        // AI 不输出 generation_status/resource_pack_id，由代码填充
                        res.putIfAbsent("generation_status", "pending");
                        res.putIfAbsent("resource_pack_id", null);
                    } else if ("matched".equals(source)) {
                        res.putIfAbsent("generation_status", null);
                        // 兜底回填 LLM 未正确填写的 resource_pack_id
                        String existingPackId = (String) res.get("resource_pack_id");
                        if (existingPackId == null || existingPackId.isBlank()) {
                            String resType = (String) res.get("resource_type");
                            String bestPackId = typeToBestPackId.get(resType);
                            if (bestPackId != null) {
                                res.put("resource_pack_id", bestPackId);
                                log.info("Backfilled resource_pack_id={} for matched type={}", bestPackId, resType);
                            }
                        }
                    }
                }
            }

            // 后校验：确保 LLM 输出的 activities 覆盖了所有 required types，
            // 漏掉的自动补入现有 learn activity 的 resources
            activities = ensureRequiredTypesCovered(activities, matchResult, canonicalRequired, moduleTitle);
            subPlanObj.put("activities", activities);

            // 保存子计划
            String subPlanJson = objectMapper.writeValueAsString(subPlanObj);
            String moduleId = (String) module.get("module_id");

            SubPlan subPlan = new SubPlan();
            subPlan.setPlanId(planObj.get("plan_id") != null ? (String) planObj.get("plan_id") : null);
            subPlan.setModuleId(moduleId);
            subPlan.setVersion(1);
            subPlan.setSubPlanJson(subPlanJson);
            subPlan.setGenerationStatus("ready");
            subPlan.setCreatedAt(LocalDateTime.now());
            subPlan.setUpdatedAt(LocalDateTime.now());
            subPlanMapper.insert(subPlan);

            // 统计需生成的资源数（所有 activities 的 resources[] 中 source=generated 的条目）
            int toGenCount = 0;
            for (Map<String, Object> a : activities) {
                List<Map<String, Object>> resources = castMapList(a.get("resources"));
                if (resources == null) continue;
                for (Map<String, Object> res : resources) {
                    if ("generated".equals(res.get("source"))) toGenCount++;
                }
            }

            return new SubPlanResult(subPlan.getId(), activities, toGenCount);

        } catch (Exception e) {
            log.error("Failed to generate sub-plan for module: {}", module.get("title"), e);
            return new SubPlanResult(null, new ArrayList<>(), 0);
        }
    }

    /**
     * 从匹配结果中提取 {资源类型 → 最佳 resource_pack_id} 映射，
     * 供子计划后处理回填 matched 活动的 resource_pack_id。
     * 优先级：matched_best > matched_stale > matched_partial
     */
    private Map<String, String> buildTypeToPackIdMap(Map<String, Object> matchResult) {
        Map<String, String> result = new LinkedHashMap<>();
        Map<String, Object> matches = castMap(matchResult.get("matches"));
        if (matches == null) return result;

        // 按优先级遍历：best → stale → partial（先出现的优先）
        for (String key : List.of("matched_best", "matched_stale", "matched_partial")) {
            List<Map<String, Object>> entries = castMapList(matches.get(key));
            for (Map<String, Object> entry : entries) {
                String type = entry.get("resource_type") instanceof String s ? s : null;
                String packId = entry.get("resource_pack_id") instanceof String s ? s : null;
                if (type != null && packId != null && !result.containsKey(type)) {
                    result.put(type, packId);
                }
            }
        }
        return result;
    }

    /**
     * 构建显式的「必须覆盖的资源类型」说明文本，直接列出每种类型及其状态，
     * 让 LLM 明确知道需要为哪些类型创建 activity。
     */
    private String buildRequiredTypesText(Map<String, Object> matchResult, List<String> canonicalRequired) {
        Map<String, Object> matchSummary = castMap(matchResult.get("match_summary"));
        List<Map<String, String>> toGenerate = castMapListOfStringMap(
                matchSummary != null ? matchSummary.get("to_generate") : null);
        Set<String> missingTypes = toGenerate.stream()
                .map(m -> m.get("type"))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 从 matches 块中收集已匹配的资源类型
        Map<String, Object> matches = castMap(matchResult.get("matches"));
        Set<String> matchedTypes = new HashSet<>();
        if (matches != null) {
            for (String key : List.of("matched_best", "matched_stale", "matched_partial")) {
                for (Map<String, Object> entry : castMapList(matches.get(key))) {
                    String t = entry.get("resource_type") instanceof String s ? s : null;
                    if (t != null) matchedTypes.add(t);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("本 module 需要覆盖的资源类型（").append(canonicalRequired.size()).append("种）：\n");
        for (String type : canonicalRequired) {
            if (matchedTypes.contains(type)) {
                sb.append(String.format("- %s → 已匹配，source=\"matched\"，需填写实际 resource_pack_id\n", type));
            } else if (missingTypes.contains(type)) {
                sb.append(String.format("- %s → ⚠️ 缺失，source=\"generated\"\n", type));
            } else {
                sb.append(String.format("- %s → 需创建，source=\"generated\"\n", type));
            }
        }
        return sb.toString();
    }

    /**
     * 后校验：确保 LLM 输出的 activities 的 resources[] 覆盖了所有 canonicalRequired 类型。
     * 漏掉的类型自动补入最后一个 learn activity 的 resources 数组，而非创建新 activity。
     */
    private List<Map<String, Object>> ensureRequiredTypesCovered(
            List<Map<String, Object>> activities,
            Map<String, Object> matchResult,
            List<String> canonicalRequired,
            String moduleTitle) {

        // 收集已有 activities 的 resources[] 覆盖的资源类型
        Set<String> coveredTypes = new HashSet<>();
        for (Map<String, Object> act : activities) {
            List<Map<String, Object>> resources = castMapList(act.get("resources"));
            if (resources == null) continue;
            for (Map<String, Object> res : resources) {
                String resType = (String) res.get("resource_type");
                if (resType != null) coveredTypes.add(resType);
            }
        }

        // 找出缺失的类型
        List<String> missingTypes = canonicalRequired.stream()
                .filter(t -> !coveredTypes.contains(t))
                .toList();

        if (missingTypes.isEmpty()) {
            log.info("All {} required types covered by LLM output for module: {}", canonicalRequired.size(), moduleTitle);
            return activities;
        }

        log.warn("LLM missed {} required types for module '{}': {}. Auto-inserting into last learn activity.",
                missingTypes.size(), moduleTitle, missingTypes);

        // 找到最后一个 learn activity，将缺失类型补入其 resources
        Map<String, Object> targetAct = null;
        for (int i = activities.size() - 1; i >= 0; i--) {
            if ("learn".equals(activities.get(i).get("type"))) {
                targetAct = activities.get(i);
                break;
            }
        }

        if (targetAct == null) {
            // 没有 learn activity，创建一个
            String actId = UUID.randomUUID().toString().substring(0, 8);
            targetAct = new LinkedHashMap<>();
            targetAct.put("activity_id", actId);
            targetAct.put("type", "learn");
            targetAct.put("title", moduleTitle);
            targetAct.put("description", "自动补全的学习活动");
            targetAct.put("requires", List.of());
            targetAct.put("resources", new ArrayList<Map<String, Object>>());
            targetAct.put("estimated_minutes", 25);
            targetAct.put("order", activities.size() + 1);
            targetAct.put("completion_criteria", Map.of("type", "all", "met", false));
            targetAct.put("status", "ready");
            targetAct.put("retry_count", 0);
            targetAct.put("result", null);
            activities.add(targetAct);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> targetResources = (List<Map<String, Object>>) targetAct.get("resources");
        if (targetResources == null) {
            targetResources = new ArrayList<>();
            targetAct.put("resources", targetResources);
        }

        for (String missingType : missingTypes) {
            Map<String, Object> newRes = new LinkedHashMap<>();
            newRes.put("source", "generated");
            newRes.put("resource_type", missingType);
            targetResources.add(newRes);
            log.info("Auto-inserted missing resource type={} into activity id={}", missingType, targetAct.get("activity_id"));
        }

        return activities;
    }

    // ==================== Phase 4: 缺口资源排队 ====================

    private List<Map<String, Object>> queueGapResourceGeneration(String taskId, String userId, Integer profileVersion,
                                             String courseId, String planId,
                                             List<Map<String, Object>> modules,
                                             List<SubPlanResult> subPlanResults) {
        int totalToGenerate = subPlanResults.stream()
                .mapToInt(SubPlanResult::toGenerateCount)
                .sum();
        log.info("Gap resources to generate: {} (triggering ResourceGenerationGraph via PLAN_DRIVEN)", totalToGenerate);

        if (totalToGenerate == 0) return List.of();

        int triggered = 0;
        List<Map<String, Object>> gapTaskInfos = new ArrayList<>();
        // 将每个子计划中所有生成的活动打包到一个任务 → 一个 ResourcePack。
        // 这自然解决了回填竞态条件：
        // 同一子计划中所有生成的活动共享同一个 packId。
        for (int i = 0; i < subPlanResults.size(); i++) {
            SubPlanResult result = subPlanResults.get(i);
            if (result.toGenerateCount() == 0) continue;

            Map<String, Object> module = i < modules.size() ? modules.get(i) : null;
            if (module == null) continue;

            String moduleId = (String) module.getOrDefault("module_id", "");
            String moduleDepth = (String) module.getOrDefault("depth", "standard");
            String moduleTitle = (String) module.getOrDefault("title", "未命名模块");

            List<String> kpNames = castMapListOfStringMap(module.get("knowledge_points")).stream()
                    .map(kp -> kp.get("name"))
                    .filter(Objects::nonNull)
                    .toList();

            // 收集此子计划的所有已生成活动
            // 每个 activity 的多种待生成类型 → 同一个 PrePlannedItem（含 resourceTypes 列表）
            List<PrePlannedItem> moduleItems = new ArrayList<>();
            List<String> allResTypes = new ArrayList<>();
            for (Map<String, Object> act : result.activities()) {
                List<Map<String, Object>> resources = castMapList(act.get("resources"));
                if (resources == null || resources.isEmpty()) continue;

                List<String> genTypes = new ArrayList<>();
                for (Map<String, Object> res : resources) {
                    if (!"generated".equals(res.get("source"))) continue;
                    String resType = (String) res.get("resource_type");
                    if (resType == null) continue;
                    if ("document".equals(resType)) resType = "doc";
                    genTypes.add(resType);
                }
                if (genTypes.isEmpty()) continue;

                String actId = (String) act.getOrDefault("activity_id", "");
                String actTitle = (String) act.getOrDefault("title", "未命名活动");
                String actDesc = (String) act.getOrDefault("description", "");
                int estMin = act.get("estimated_minutes") instanceof Number n
                        ? n.intValue() : 15;

                moduleItems.add(new PrePlannedItem(
                        actId, actTitle, actDesc, genTypes,
                        kpNames, moduleDepth, estMin));
                allResTypes.addAll(genTypes);
            }

            if (moduleItems.isEmpty()) continue;

            PlanDrivenGenerationRequest planCtx = new PlanDrivenGenerationRequest(
                    planId, moduleId, result.subPlanId(), moduleItems);

            try {
                String genTaskId = taskOrchestrator.createTask(userId,
                        new TaskOrchestrator.TaskCreateRequest(
                                courseId,
                                moduleTitle,
                                allResTypes.stream().distinct().toList(),
                                profileVersion != null ? profileVersion : 1,
                                null,
                                planCtx));
                log.info("Triggered plan-driven batch generation: taskId={}, subPlan={}, items={}, types={}",
                        genTaskId, result.subPlanId(), moduleItems.size(), allResTypes);
                triggered++;

                Map<String, Object> taskInfo = new LinkedHashMap<>();
                taskInfo.put("task_id", genTaskId);
                taskInfo.put("module_title", moduleTitle);
                taskInfo.put("module_id", moduleId);
                taskInfo.put("resource_types", allResTypes.stream().distinct().toList());
                gapTaskInfos.add(taskInfo);
            } catch (Exception e) {
                log.error("Failed to trigger batch generation for subPlan: {}", result.subPlanId(), e);
            }
        }
        log.info("Gap resource generation triggered: {} tasks for {} total resources (PLAN_DRIVEN)",
                triggered, totalToGenerate);
        return gapTaskInfos;
    }

    /**
     * 轮询等待所有缺口资源任务完成，同时更新计划任务进度
     */
    private void waitForGapTasks(String planTaskId, List<String> gapTaskIds) {
        int total = gapTaskIds.size();
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10);

        while (true) {
            long completed = gapTaskIds.stream()
                    .map(id -> taskPersistence.getTaskStatus(id))
                    .filter(status -> "SUCCEEDED".equals(status)
                            || "FAILED".equals(status)
                            || "CANCELLED".equals(status))
                    .count();

            int percent = 85 + (int) ((double) completed / total * 15);
            advance(planTaskId, "GENERATING", percent,
                    "缺口资源生成中 (" + completed + "/" + total + ")");

            if (completed >= total) break;
            if (System.currentTimeMillis() > deadline) {
                log.warn("Gap task wait timeout for plan: planTaskId={}, completed={}/{}", planTaskId, completed, total);
                break;
            }

            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.info("Gap tasks completed for plan: planTaskId={}", planTaskId);
    }

    // ==================== 持久化 ====================

    /**
     * 保存学习计划（参数化 status），同时写入版本快照
     */
    private String saveLearningPlanWithStatus(String userId, String courseId, Integer profileVersion,
                                               String planJson, String status, String chatId) {
        try {
            Map<String, Object> planObj = objectMapper.readValue(planJson, mapType());

            LearningPlan existing = planMapper.findByUserIdAndCourseId(userId, courseId);

            String planId;
            int newVersion;

            if (existing != null) {
                planId = existing.getId();
                newVersion = existing.getCurrentVersion() + 1;

                planObj.put("plan_id", planId);
                String updatedPlanJson = objectMapper.writeValueAsString(planObj);

                existing.setCurrentVersion(newVersion);
                existing.setPlanJson(updatedPlanJson);
                existing.setProfileVersion(profileVersion != null ? profileVersion : 1);
                existing.setStatus(status);
                if (chatId != null) existing.setChatId(chatId);
                existing.setUpdatedAt(LocalDateTime.now());
                planMapper.updateById(existing);

                log.info("Learning plan updated: planId={}, newVersion={}, status={}", planId, newVersion, status);
            } else {
                planId = UUID.randomUUID().toString();
                newVersion = 1;

                planObj.put("plan_id", planId);
                String finalPlanJson = objectMapper.writeValueAsString(planObj);

                LearningPlan plan = new LearningPlan();
                plan.setId(planId);
                plan.setUserId(userId);
                plan.setCourseId(courseId);
                plan.setProfileVersion(profileVersion != null ? profileVersion : 1);
                plan.setCurrentVersion(newVersion);
                plan.setPlanJson(finalPlanJson);
                plan.setStatus(status);
                plan.setLockMode("sequential");
                if (chatId != null) plan.setChatId(chatId);
                plan.setCreatedAt(LocalDateTime.now());
                plan.setUpdatedAt(LocalDateTime.now());
                planMapper.insert(plan);

                log.info("Learning plan created: planId={}", planId);
            }

            // 写入版本快照
            String finalPlanJson = objectMapper.writeValueAsString(planObj);
            LearningPlanVersion version = new LearningPlanVersion();
            version.setPlanId(planId);
            version.setUserId(userId);
            version.setCourseId(courseId);
            version.setVersion(newVersion);
            version.setPlanJson(finalPlanJson);
            version.setCreatedAt(LocalDateTime.now());
            planVersionMapper.insert(version);

            return planId;
        } catch (Exception e) {
            log.error("Failed to save learning plan", e);
            throw new RuntimeException("学习计划保存失败", e);
        }
    }

    /** 保存学习计划（status=ready），兼容旧调用 */
    private String saveLearningPlan(String userId, String courseId, Integer profileVersion, String planJson) {
        return saveLearningPlanWithStatus(userId, courseId, profileVersion, planJson, "ready", null);
    }

    // ==================== 辅助方法 ====================

    private ProfileVersion loadProfile(String userId, String courseId, Integer profileVersion) {
        if (profileVersion == null) return null;
        try {
            return profileVersionMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProfileVersion>()
                            .eq(ProfileVersion::getUserId, userId)
                            .eq(ProfileVersion::getCourseId, courseId)
                            .eq(ProfileVersion::getVersion, profileVersion));
        } catch (Exception e) {
            log.warn("Failed to load profile version", e);
            return null;
        }
    }

    private String buildKpTreeJson(List<CourseKnowledgePoint> kps) {
        StringBuilder sb = new StringBuilder();
        for (CourseKnowledgePoint kp : kps) {
            sb.append(String.format("- [%s] %s (%s) scope=%s", kp.getId(), kp.getName(),
                    kp.getKpType(), kp.getScope()));
            if (kp.getPrerequisiteKps() != null && !kp.getPrerequisiteKps().isBlank()) {
                sb.append(" prerequisites=").append(kp.getPrerequisiteKps());
            }
            if (kp.getKeywords() != null && !kp.getKeywords().isBlank()) {
                sb.append(" keywords=").append(kp.getKeywords());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private List<CourseKnowledgePoint> extractModuleKps(List<CourseKnowledgePoint> allKps, Map<String, Object> module) {
        List<Map<String, String>> kpRefs = castMapListOfStringMap(module.get("knowledge_points"));
        if (kpRefs == null) return List.of();

        Set<String> kpIds = kpRefs.stream()
                .map(m -> m.get("kp_id"))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return allKps.stream()
                .filter(kp -> kpIds.contains(kp.getId()))
                .collect(Collectors.toList());
    }

    private String buildPlanOverview(Map<String, Object> planObj) {
        List<Map<String, Object>> modules = castMapList(planObj.get("modules"));
        Map<String, Object> summary = castMap(planObj.get("summary"));

        StringBuilder sb = new StringBuilder();
        sb.append("学习大计划概览：\n");

        for (Map<String, Object> m : modules) {
            sb.append(String.format("- %s (%s, %s, %sh, %s)",
                    m.get("module_id"), m.get("title"), m.get("depth"),
                    m.get("estimated_hours"), m.get("scope")));
            List<String> prereqs = castStringList(m.get("prerequisites"));
            if (prereqs != null && !prereqs.isEmpty()) {
                sb.append(" 前置: ").append(prereqs);
            }
            sb.append("\n");
        }

        if (summary != null) {
            sb.append(String.format("总计: %s 模块, %s 小时",
                    summary.get("total_modules"), summary.get("total_hours")));
        }
        return sb.toString();
    }

    private void advance(String taskId, String stage, int percent, String message) {
        taskPersistence.recordStageEvent(taskId, stage, percent, message, null);
        broadcaster.broadcastStage(taskId, stage, percent, message, null);

        // 更新 Redis 热数据
        String status = percent >= 100 ? "SUCCEEDED" : "RUNNING";
        taskPersistence.updateTaskStage(taskId, stage, percent, status);
    }

    private String extractJson(String text) {
        if (text == null) return "{}";
        text = text.trim();
        // 移除 markdown 代码围栏
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            if (start > 0) text = text.substring(start);
            int end = text.lastIndexOf("```");
            if (end > 0) text = text.substring(0, end);
        }
        // 查找第一个 { 和最后一个 }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private String extractNestedField(Map<String, Object> map, String field, String defaultValue) {
        Object val = map.get(field);
        if (val == null) return defaultValue;
        if (val instanceof Map) {
            try {
                return objectMapper.writeValueAsString(val);
            } catch (JsonProcessingException e) {
                return val.toString();
            }
        }
        return val.toString();
    }

    private String extractNestedField(Map<String, Object> map, String field) {
        return extractNestedField(map, field, "未提供");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeParseJson(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
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

    private com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> mapType() {
        return new com.fasterxml.jackson.core.type.TypeReference<>() {};
    }

    private record SubPlanResult(String subPlanId, List<Map<String, Object>> activities, int toGenerateCount) {}
}
