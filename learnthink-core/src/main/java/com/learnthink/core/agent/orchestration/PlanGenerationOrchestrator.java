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
            ResourceItemMapper resourceItemMapper) {
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
    }

    /**
     * 异步启动学习计划生成任务
     */
    public String startGeneration(String userId, String courseId, Integer profileVersion) {
        String taskId = UUID.randomUUID().toString();

        // 1. 创建任务记录
        taskPersistence.createTask(taskId, userId, courseId, "plan_generate",
                "学习计划生成", "[\"plan\"]", profileVersion, null);
        taskPersistence.updateTaskStage(taskId, "INIT", 0, "PENDING");
        broadcaster.taskAccepted(taskId, java.time.Instant.now());

        // 2. 异步执行
        CompletableFuture.runAsync(() -> executeGeneration(taskId, userId, courseId, profileVersion));

        return taskId;
    }

    private void executeGeneration(String taskId, String userId, String courseId, Integer profileVersion) {
        try {
            taskPersistence.updateTaskStage(taskId, "PLANNING", 0, "RUNNING");
            advance(taskId, "PLANNING", 5, "正在加载画像与课程知识图谱...");

            // === Phase 0: 加载上下文 ===
            ProfileVersion profile = loadProfile(userId, courseId, profileVersion);
            List<CourseKnowledgePoint> kpTree = kpMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CourseKnowledgePoint>()
                            .eq(CourseKnowledgePoint::getCourseId, courseId)
                            .orderByAsc(CourseKnowledgePoint::getSortOrder));

            String profileSummary = profile != null ? profile.getSummaryJson() : "{}";
            String courseKpTreeJson = buildKpTreeJson(kpTree);

            advance(taskId, "PLANNING", 10, "正在分析课程结构，生成学习大计划...");

            // === Phase 1: 大计划生成 ===
            Map<String, Object> bigPlan = generateBigPlan(courseKpTreeJson, profileSummary, userId, courseId, profileVersion);
            List<Map<String, Object>> modules = castMapList(bigPlan.get("modules"));
            List<Map<String, Object>> edges = castMapList(bigPlan.get("edges"));
            Map<String, Object> summary = castMap(bigPlan.get("summary"));

            log.info("Big plan generated: {} modules, {} edges", modules.size(), edges.size());

            // 保存大计划
            String planJson = objectMapper.writeValueAsString(bigPlan);
            String planId = saveLearningPlan(userId, courseId, profileVersion, planJson);

            advance(taskId, "PLANNING", 35, "大计划已生成 — " + modules.size() + " 个学习模块");

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

            // === Phase 3: 子计划并行生成 ===
            advance(taskId, "SUBPLANNING", 55, "正在为每个模块规划详细学习活动...");
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

            // 更新 plan_json（含 sub_plan_id 回填）
            String updatedPlanJson = objectMapper.writeValueAsString(bigPlan);
            planMapper.updatePlan(planId, profileVersion != null ? profileVersion : 1, updatedPlanJson);

            advance(taskId, "SUBPLANNING", 80, "子计划全部就绪");

            // === Phase 4: 缺口资源生成（排队）===
            int genCount = 0;
            for (SubPlanResult r : subPlanResults) {
                genCount += r.toGenerateCount();
            }
            if (genCount > 0) {
                advance(taskId, "GENERATING", 85, "缺口资源生成就绪 (" + genCount + " 份待生成)");
                queueGapResourceGeneration(taskId, userId, profileVersion, courseId, modules, subPlanResults);
            }

            // === 完成 ===
            advance(taskId, "COMPLETED", 100, "学习计划生成完成");
            taskPersistence.updateTaskStage(taskId, "COMPLETED", 100, "SUCCEEDED");
            broadcaster.taskDone(taskId, "SUCCEEDED", planId, modules.size(), Set.of());

            int finalMatched = totalMatched;
            int finalGen = genCount;
            broadcaster.broadcastEvent(taskId, "plan.done", Map.of(
                    "plan_id", planId,
                    "modules", modules.size(),
                    "matched_resources", finalMatched,
                    "generated_resources", finalGen
            ));

            log.info("Plan generation completed: taskId={}, planId={}, modules={}, matched={}, toGenerate={}",
                    taskId, planId, modules.size(), totalMatched, genCount);

        } catch (Exception e) {
            log.error("Plan generation failed: taskId={}", taskId, e);
            taskPersistence.failTask(taskId, "PLAN_GENERATION_ERROR", e.getMessage());
            broadcaster.taskFailed(taskId, "PLAN_GENERATION_ERROR", e.getMessage(), false);
        }
    }

    // ==================== Phase 1: 大计划 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> generateBigPlan(String courseKpTreeJson, String profileSummary,
                                                  String userId, String courseId, Integer profileVersion) {
        String prompt = promptLoader.get("plan/big_plan")
                .replace("{course_kp_tree}", courseKpTreeJson)
                .replace("{profile_summary}", profileSummary)
                .replace("{learning_goal}", extractField(profileSummary, "learning_goal"))
                .replace("{knowledge_basis}", extractField(profileSummary, "knowledge_basis"))
                .replace("{learning_pace}", extractField(profileSummary, "learning_pace"))
                .replace("{interest_direction}", extractField(profileSummary, "interest_direction"))
                .replace("{major_context}", extractField(profileSummary, "major_context"));

        String content = reasoningChatClient.prompt()
                .messages(
                        new org.springframework.ai.chat.messages.SystemMessage(prompt),
                        new org.springframework.ai.chat.messages.UserMessage("请根据课程知识图谱和画像信息，生成一份个性化的学习大计划。"))
                .call()
                .content();

        try {
            // Try to extract JSON from the LLM response (it may include markdown fences)
            String json = extractJson(content);
            return objectMapper.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            log.error("Failed to parse big plan LLM response", e);
            throw new RuntimeException("大计划生成失败：LLM 输出解析错误", e);
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
                // Step 1: KP 标签匹配（简化：检查 pack 中的 topic 是否包含 module KP 关键词）
                String packTopic = pack.getTopic() != null ? pack.getTopic() : "";
                long kpMatchCount = kpNames.stream().filter(kp -> packTopic.contains(kp) || kp.contains(packTopic)).count();

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
            String moduleKpsStr = moduleKps.stream()
                    .map(kp -> kp.getName() + "：" + (kp.getDescription() != null ? kp.getDescription() : ""))
                    .collect(Collectors.joining("\n"));

            String matchSummaryJson = objectMapper.writeValueAsString(
                    matchResult.getOrDefault("match_summary", Map.of()));

            // 构建 plan_overview
            Map<String, Object> planObj = objectMapper.readValue(planJson, mapType());
            String planOverview = buildPlanOverview(planObj);

            // 从画像提取认知风格和错误模式
            String profileJson = profile != null ? profile.getSummaryJson() : "{}";
            Map<String, Object> profileMap = safeParseJson(profileJson);

            String prompt = promptLoader.get("plan/sub_plan")
                    .replace("{plan_overview}", planOverview)
                    .replace("{module_title}", moduleTitle)
                    .replace("{module_kps}", moduleKpsStr)
                    .replace("{module_depth}", (String) module.getOrDefault("depth", "standard"))
                    .replace("{module_scope}", (String) module.getOrDefault("scope", "core_curriculum"))
                    .replace("{module_prerequisites}", String.valueOf(module.getOrDefault("prerequisites", List.of())))
                    .replace("{match_summary}", matchSummaryJson)
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

            // 统计需生成的资源数
            int toGenCount = castMapList(subPlanObj.get("activities")).stream()
                    .filter(a -> {
                        Map<String, Object> res = castMap(a.get("resource"));
                        return res != null && "generated".equals(res.get("source"));
                    })
                    .toList()
                    .size();

            return new SubPlanResult(subPlan.getId(), activities, toGenCount);

        } catch (Exception e) {
            log.error("Failed to generate sub-plan for module: {}", module.get("title"), e);
            return new SubPlanResult(null, new ArrayList<>(), 0);
        }
    }

    // ==================== Phase 4: 缺口资源排队 ====================

    private void queueGapResourceGeneration(String taskId, String userId, Integer profileVersion,
                                             String courseId, List<Map<String, Object>> modules,
                                             List<SubPlanResult> subPlanResults) {
        int totalToGenerate = subPlanResults.stream()
                .mapToInt(SubPlanResult::toGenerateCount)
                .sum();
        log.info("Gap resources to generate: {} (triggering ResourceGenerationGraph)", totalToGenerate);

        if (totalToGenerate == 0) return;

        int triggered = 0;
        for (int i = 0; i < subPlanResults.size(); i++) {
            SubPlanResult result = subPlanResults.get(i);
            if (result.toGenerateCount() == 0) continue;

            // Collect resource types from activities that need generation
            Set<String> resourceTypes = new LinkedHashSet<>();
            for (Map<String, Object> act : result.activities()) {
                Map<String, Object> res = castMap(act.get("resource"));
                if (res != null && "generated".equals(res.get("source"))) {
                    String type = (String) res.get("resource_type");
                    if (type != null) resourceTypes.add(type);
                }
            }

            if (resourceTypes.isEmpty()) continue;

            // Use module title as topic for the generation task
            String moduleTitle = i < modules.size()
                    ? (String) modules.get(i).getOrDefault("title", "学习模块")
                    : "学习模块";

            try {
                String genTaskId = taskOrchestrator.createTask(userId,
                        new TaskOrchestrator.TaskCreateRequest(
                                courseId,
                                "【计划缺口】" + moduleTitle,
                                new ArrayList<>(resourceTypes),
                                profileVersion != null ? profileVersion : 1,
                                null));
                log.info("Triggered gap resource generation: taskId={}, module={}, types={}",
                        genTaskId, moduleTitle, resourceTypes);
                triggered++;
            } catch (Exception e) {
                log.error("Failed to trigger gap resource generation for module: {}", moduleTitle, e);
            }
        }
        log.info("Gap resource generation triggered: {} tasks for {} total resources",
                triggered, totalToGenerate);
    }

    // ==================== 持久化 ====================

    private String saveLearningPlan(String userId, String courseId, Integer profileVersion, String planJson) {
        try {
            Map<String, Object> planObj = objectMapper.readValue(planJson, mapType());
            planObj.put("plan_id", UUID.randomUUID().toString());

            String finalPlanJson = objectMapper.writeValueAsString(planObj);

            LearningPlan plan = new LearningPlan();
            plan.setId(UUID.randomUUID().toString());
            plan.setUserId(userId);
            plan.setCourseId(courseId);
            plan.setProfileVersion(profileVersion != null ? profileVersion : 1);
            plan.setCurrentVersion(1);
            plan.setPlanJson(finalPlanJson);
            plan.setStatus("ready");
            plan.setCreatedAt(LocalDateTime.now());
            plan.setUpdatedAt(LocalDateTime.now());
            planMapper.insert(plan);

            // 写入版本快照
            LearningPlanVersion version = new LearningPlanVersion();
            version.setPlanId(plan.getId());
            version.setUserId(userId);
            version.setCourseId(courseId);
            version.setVersion(1);
            version.setPlanJson(finalPlanJson);
            version.setCreatedAt(LocalDateTime.now());
            planVersionMapper.insert(version);

            return plan.getId();
        } catch (Exception e) {
            log.error("Failed to save learning plan", e);
            throw new RuntimeException("学习计划保存失败", e);
        }
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

        // Update Redis hot data
        String status = percent >= 100 ? "SUCCEEDED" : "RUNNING";
        taskPersistence.updateTaskStage(taskId, stage, percent, status);
    }

    private String extractJson(String text) {
        if (text == null) return "{}";
        text = text.trim();
        // Remove markdown code fences
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            if (start > 0) text = text.substring(start);
            int end = text.lastIndexOf("```");
            if (end > 0) text = text.substring(0, end);
        }
        // Find first { and last }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private String extractField(String profileSummary, String field) {
        if (profileSummary == null || profileSummary.isBlank()) return "未提供";
        try {
            Map<String, Object> map = safeParseJson(profileSummary);
            Object val = map.get(field);
            if (val instanceof Map) {
                return objectMapper.writeValueAsString(val);
            }
            return val != null ? val.toString() : "未提供";
        } catch (Exception e) {
            return "未提供";
        }
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
