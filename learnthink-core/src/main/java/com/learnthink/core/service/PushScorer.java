package com.learnthink.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.push.PushReason;
import com.learnthink.common.dto.push.ScoredPack;
import com.learnthink.core.config.PushScoringProperties;
import com.learnthink.core.domain.entity.*;
import com.learnthink.core.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 推送评分器 - 基于多级优先级排序的候选资源包评分与排序
 *
 * <p>排序规则：path_match DESC -> weakness_match DESC -> interest_match DESC -> created_at DESC</p>
 *
 * <p>性能设计：通过 {@link ScoringContext} 在评分循环外一次性批量加载所有依赖数据，
 * 循环内零数据库查询，将单次推荐的 DB 查询数从 200+ 降至 6-8 次。</p>
 *
 * <p>匹配精度：三层匹配策略 - 精确包含 -> bigram 分词 -> 关键词兜底</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushScorer {

    private final ResourcePackMapper resourcePackMapper;
    private final ResourceItemMapper resourceItemMapper;
    private final NotificationMapper notificationMapper;
    private final LearningPlanMapper learningPlanMapper;
    private final SubPlanMapper subPlanMapper;
    private final ProfileVersionMapper profileVersionMapper;
    private final ProfileKpAnchorMapper profileKpAnchorMapper;
    private final CourseKnowledgePointMapper kpMapper;
    private final QuizAttemptMapper quizAttemptMapper;
    private final ObjectMapper objectMapper;
    private final PushScoringProperties scoringProps;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    // ==================== 评分入口 ====================

    /**
     * 对候选资源包评分并返回 Top-N
     */
    public List<ScoredPack> scoreCandidates(String userId, String courseId, int limit) {
        // 1. 获取候选池（已推送过的排除）
        List<ResourcePack> candidates = getFilteredCandidates(userId, courseId);
        if (candidates.isEmpty()) return List.of();

        // 2. 一次性构建评分上下文（6-8 次查询）
        ScoringContext ctx = buildScoringContext(userId, courseId, candidates);

        // 3. 纯内存评分（零数据库查询）
        List<ScoredPack> scored = new ArrayList<>();
        for (ResourcePack pack : candidates) {
            List<ResourceItem> items = ctx.itemsByPackId.getOrDefault(pack.getId(), List.of());

            double pathMatch = calcPathMatchInMemory(pack, items, ctx);
            double weakness = calcWeaknessMatchInMemory(pack, items, ctx);
            double interest = calcInterestMatchInMemory(pack, items, ctx);

            List<PushReason> reasons = collectReasons(pathMatch, weakness, interest, pack, items, ctx);

            scored.add(buildScoredPackInMemory(pack, items, pathMatch, weakness, interest, reasons));
        }

        // 4. 多级优先级排序（不用加权公式）
        scored.sort(this::compareByPriority);

        return scored.subList(0, Math.min(limit, scored.size()));
    }

    /**
     * 获取单个资源包的评分（用于事件驱动推送时复用评分能力）
     */
    public ScoredPack scoreSingle(String packId, String userId, String courseId) {
        ResourcePack pack = resourcePackMapper.selectById(packId);
        if (pack == null) return null;

        ScoringContext ctx = buildScoringContext(userId, courseId, List.of(pack));

        List<ResourceItem> items = ctx.itemsByPackId.getOrDefault(packId, List.of());
        double pathMatch = calcPathMatchInMemory(pack, items, ctx);
        double weakness = calcWeaknessMatchInMemory(pack, items, ctx);
        double interest = calcInterestMatchInMemory(pack, items, ctx);

        List<PushReason> reasons = collectReasons(pathMatch, weakness, interest, pack, items, ctx);

        return buildScoredPackInMemory(pack, items, pathMatch, weakness, interest, reasons);
    }

    // ==================== 候选池过滤 ====================

    private List<ResourcePack> getFilteredCandidates(String userId, String courseId) {
        // 查已推送过的 pack ID（只排除真正推送过的，冷却期内 isPushed=false 的不排除）
        List<String> pushedPackIds = notificationMapper.selectList(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, userId)
                        .likeRight(Notification::getType, "push_")
                        .eq(Notification::getRefType, "pack")
                        .eq(Notification::getIsPushed, true)
                        .select(Notification::getRefId)
        ).stream().map(Notification::getRefId).filter(Objects::nonNull).distinct().toList();

        LambdaQueryWrapper<ResourcePack> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ResourcePack::getUserId, userId)
                .eq(ResourcePack::getCourseId, courseId)
                .isNull(ResourcePack::getDeletedAt);
        if (!pushedPackIds.isEmpty()) {
            wrapper.notIn(ResourcePack::getId, pushedPackIds);
        }
        wrapper.orderByDesc(ResourcePack::getCreatedAt)
                .last("LIMIT " + scoringProps.getCandidatePoolLimit());

        return resourcePackMapper.selectList(wrapper);
    }

    // ==================== ScoringContext 批量预加载 ====================

    /**
     * 评分上下文 - 预加载所有评分所需数据，循环内零数据库查询
     */
    private static class ScoringContext {
        Map<String, List<ResourceItem>> itemsByPackId;
        LearningPlan plan;
        List<Map<String, Object>> modules;
        int currentModuleIdx = -1;
        String currentModuleName;
        String nextModuleName;
        List<String> unlearnedKpNames;
        ProfileVersion profile;
        Set<String> weakTags;
        Set<String> interestTags;
        Map<String, CourseKnowledgePoint> kpByIdMap;
        Map<String, CourseKnowledgePoint> kpByNameMap;
        Map<String, List<String>> keywordToKpNamesIndex;
    }

    private ScoringContext buildScoringContext(String userId, String courseId,
                                                List<ResourcePack> candidates) {
        ScoringContext ctx = new ScoringContext();

        // ---- 查询 1：批量加载所有候选包的 resource_items ----
        if (!candidates.isEmpty()) {
            List<String> packIds = candidates.stream().map(ResourcePack::getId).toList();
            List<ResourceItem> allItems = resourceItemMapper.selectList(
                    new LambdaQueryWrapper<ResourceItem>()
                            .in(ResourceItem::getPackId, packIds)
                            .isNull(ResourceItem::getDeletedAt));
            ctx.itemsByPackId = allItems.stream()
                    .collect(Collectors.groupingBy(ResourceItem::getPackId));
        } else {
            ctx.itemsByPackId = new HashMap<>();
        }

        // ---- 查询 2：学习计划 ----
        ctx.plan = learningPlanMapper.findByUserIdAndCourseId(userId, courseId);
        if (ctx.plan != null) {
            ctx.modules = parsePlanModules(ctx.plan);
            if (ctx.modules != null && !ctx.modules.isEmpty()) {
                ctx.currentModuleIdx = findCurrentModuleIndex(ctx.modules);
                resolveModuleContext(ctx);
            }
        }

        // ---- 查询 3：用户最新画像 ----
        ctx.profile = profileVersionMapper.selectOne(
                new LambdaQueryWrapper<ProfileVersion>()
                        .eq(ProfileVersion::getUserId, userId)
                        .eq(ProfileVersion::getCourseId, courseId)
                        .orderByDesc(ProfileVersion::getCreatedAt)
                        .last("LIMIT 1"));

        // ---- 查询 4：课程知识点图谱（全量）----
        List<CourseKnowledgePoint> allKps = kpMapper.selectList(
                new LambdaQueryWrapper<CourseKnowledgePoint>()
                        .eq(CourseKnowledgePoint::getCourseId, courseId));
        ctx.kpByIdMap = allKps.stream()
                .collect(Collectors.toMap(CourseKnowledgePoint::getId, k -> k, (a, b) -> a));
        ctx.kpByNameMap = allKps.stream()
                .collect(Collectors.toMap(CourseKnowledgePoint::getName, k -> k, (a, b) -> a));
        ctx.keywordToKpNamesIndex = buildKeywordIndex(allKps);

        // ---- 查询 5：画像 KP anchors（薄弱 + 兴趣，一次查完）----
        ctx.weakTags = new HashSet<>();
        ctx.interestTags = new HashSet<>();
        if (ctx.profile != null) {
            List<ProfileKpAnchor> anchors = profileKpAnchorMapper.selectList(
                    new LambdaQueryWrapper<ProfileKpAnchor>()
                            .eq(ProfileKpAnchor::getProfileVersionId, ctx.profile.getId())
                            .in(ProfileKpAnchor::getRelationType, "weak", "interest"));
            for (ProfileKpAnchor anchor : anchors) {
                String kpName = getKpNameByIdInMemory(anchor.getKpId(), ctx);
                if (kpName == null) continue;
                if ("weak".equals(anchor.getRelationType())) {
                    ctx.weakTags.add(kpName);
                } else if ("interest".equals(anchor.getRelationType())) {
                    ctx.interestTags.add(kpName);
                }
            }
        }

        // ---- 查询 6：最近 quiz 薄弱标签 ----
        List<String> recentWeakTags = getRecentWeakTags(userId, courseId, scoringProps.getWeakTagsRecentDays());
        for (String tag : recentWeakTags) {
            String kpName = fuzzyMatchKpNameInMemory(tag, ctx);
            ctx.weakTags.add(kpName != null ? kpName : tag);
        }

        return ctx;
    }

    private void resolveModuleContext(ScoringContext ctx) {
        if (ctx.currentModuleIdx < 0 || ctx.currentModuleIdx >= ctx.modules.size()) return;

        Map<String, Object> currentModule = ctx.modules.get(ctx.currentModuleIdx);
        ctx.currentModuleName = (String) currentModule.get("title");

        int nextIdx = ctx.currentModuleIdx + 1;
        if (nextIdx < ctx.modules.size()) {
            ctx.nextModuleName = (String) ctx.modules.get(nextIdx).get("title");
        }

        String subPlanId = (String) currentModule.get("sub_plan_id");
        if (subPlanId != null) {
            SubPlan sp = subPlanMapper.selectById(subPlanId);
            if (sp != null) {
                ctx.unlearnedKpNames = extractUnlearnedKps(sp);
            }
        }
    }

    // ==================== 内存版评分方法 ====================

    // ---------- 路径匹配 (path_match) ----------

    double calcPathMatchInMemory(ResourcePack pack, List<ResourceItem> items, ScoringContext ctx) {
        if (ctx.plan == null || ctx.modules == null || ctx.modules.isEmpty()) return 0.0;
        if (ctx.currentModuleIdx == -1) return 0.0;

        double nextScore = 0.0;
        double currentScore = 0.0;

        // 下一模块匹配
        int nextIdx = ctx.currentModuleIdx + 1;
        if (nextIdx < ctx.modules.size()) {
            nextScore = matchPackAgainstModuleInMemory(pack, items, ctx.modules.get(nextIdx), ctx);
        }

        // 当前模块未学知识点匹配
        if (ctx.unlearnedKpNames != null && !ctx.unlearnedKpNames.isEmpty()) {
            long matchCount = ctx.unlearnedKpNames.stream()
                    .filter(kpName -> kpMatchesPackInMemory(kpName, pack, items, ctx))
                    .count();
            if (matchCount > 0) {
                currentScore = Math.min(1.0,
                        matchCount / (double) ctx.unlearnedKpNames.size() + scoringProps.getPathMatchBonusCurrent());
            }
        }

        // 取两者较大值（评分反映实际相关性）
        return Math.max(nextScore, currentScore);
    }

    private double matchPackAgainstModuleInMemory(ResourcePack pack, List<ResourceItem> items,
                                                   Map<String, Object> module, ScoringContext ctx) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> kps = (List<Map<String, Object>>) module.get("knowledge_points");
        if (kps == null || kps.isEmpty()) return 0.0;

        List<String> moduleKpNames = kps.stream()
                .map(kp -> (String) kp.get("name"))
                .filter(Objects::nonNull)
                .toList();
        if (moduleKpNames.isEmpty()) return 0.0;

        long matchCount = moduleKpNames.stream()
                .filter(kp -> kpMatchesPackInMemory(kp, pack, items, ctx))
                .count();

        return matchCount > 0 ? Math.min(1.0,
                matchCount / (double) moduleKpNames.size() + scoringProps.getPathMatchBonusNext()) : 0.0;
    }

    // ---------- 薄弱知识点匹配 (weakness_match) ----------

    double calcWeaknessMatchInMemory(ResourcePack pack, List<ResourceItem> items, ScoringContext ctx) {
        if (ctx.weakTags == null || ctx.weakTags.isEmpty()) return 0.0;

        long matchCount = ctx.weakTags.stream()
                .filter(kpName -> kpMatchesPackInMemory(kpName, pack, items, ctx))
                .count();

        if (matchCount == 0) return 0.0;
        double ratio = matchCount / (double) ctx.weakTags.size();
        return Math.min(1.0, ratio + scoringProps.getWeaknessMatchBonus());
    }

    // ---------- 兴趣匹配 (interest_match) ----------

    double calcInterestMatchInMemory(ResourcePack pack, List<ResourceItem> items, ScoringContext ctx) {
        if (ctx.interestTags == null || ctx.interestTags.isEmpty()) return 0.0;

        // 兴趣匹配只检查 pack topic，不检查 resource item title（与原逻辑一致）
        long matchCount = ctx.interestTags.stream()
                .filter(kpName -> kpMatchesTopicInMemory(kpName, pack, ctx))
                .count();

        if (matchCount == 0) return 0.0;
        return Math.min(1.0,
                matchCount / Math.max(1, ctx.interestTags.size()) + scoringProps.getInterestMatchBonus());
    }

    // ---------- KP 匹配方法（三层：精确包含 -> bigram -> 关键词）----------

    /**
     * 知识点匹配资源包：检查 topic -> items title -> bigram -> keywords
     * 用于路径匹配和薄弱匹配
     */
    private boolean kpMatchesPackInMemory(String kpName, ResourcePack pack,
                                           List<ResourceItem> items, ScoringContext ctx) {
        // 层 1：精确包含
        if (pack.getTopic() != null && pack.getTopic().contains(kpName)) return true;
        for (ResourceItem item : items) {
            if (item.getTitle() != null && item.getTitle().contains(kpName)) return true;
        }
        // 层 2：bigram 分词匹配
        if (pack.getTopic() != null && bigramMatch(kpName, pack.getTopic())) return true;
        for (ResourceItem item : items) {
            if (item.getTitle() != null && bigramMatch(kpName, item.getTitle())) return true;
        }
        // 层 3：关键词兜底
        List<String> keywords = getKeywordsForKpNameInMemory(kpName, ctx);
        for (String kw : keywords) {
            if (pack.getTopic() != null && pack.getTopic().contains(kw)) return true;
        }
        return false;
    }

    /**
     * 知识点匹配 topic：检查 topic -> bigram -> keywords
     * 用于兴趣匹配（不检查 items title）
     */
    private boolean kpMatchesTopicInMemory(String kpName, ResourcePack pack, ScoringContext ctx) {
        String topic = pack.getTopic() != null ? pack.getTopic() : "";
        // 层 1：精确包含
        if (topic.contains(kpName)) return true;
        // 层 2：bigram 分词匹配
        if (bigramMatch(kpName, topic)) return true;
        // 层 3：关键词兜底
        List<String> keywords = getKeywordsForKpNameInMemory(kpName, ctx);
        for (String kw : keywords) {
            if (topic.contains(kw)) return true;
        }
        return false;
    }

    /**
     * 中文 bigram 分词 - 将文本切分为 2 字符滑动窗口
     */
    private Set<String> tokenizeBigram(String text) {
        if (text == null || text.length() < 2) return Set.of();
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < text.length() - 1; i++) {
            tokens.add(text.substring(i, i + 2));
        }
        return tokens;
    }

    /**
     * bigram 分词匹配 - 知识点名与文本的 bigram 交集比例超过阈值则判定匹配
     */
    private boolean bigramMatch(String kpName, String text) {
        if (text == null || text.length() < 2 || kpName == null || kpName.length() < 2) return false;
        Set<String> textTokens = tokenizeBigram(text);
        Set<String> kpTokens = tokenizeBigram(kpName);
        if (kpTokens.isEmpty()) return false;
        long overlap = kpTokens.stream().filter(textTokens::contains).count();
        return (double) overlap / kpTokens.size() > scoringProps.getBigramMatchThreshold();
    }

    /**
     * 从内存索引获取知识点的 keywords
     */
    private List<String> getKeywordsForKpNameInMemory(String kpName, ScoringContext ctx) {
        CourseKnowledgePoint kp = ctx.kpByNameMap.get(kpName);
        if (kp == null || kp.getKeywords() == null) return List.of();
        try {
            return objectMapper.readValue(kp.getKeywords(), STRING_LIST_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }

    // ==================== KP 名称 / 关键词内存查询 ====================

    private String getKpNameByIdInMemory(String kpId, ScoringContext ctx) {
        if (kpId == null) return null;
        CourseKnowledgePoint kp = ctx.kpByIdMap.get(kpId);
        return kp != null ? kp.getName() : null;
    }

    private String fuzzyMatchKpNameInMemory(String tag, ScoringContext ctx) {
        if (tag == null || tag.isBlank()) return null;
        if (ctx.kpByNameMap.containsKey(tag)) return tag;
        for (CourseKnowledgePoint kp : ctx.kpByNameMap.values()) {
            if (kp.getKeywords() == null) continue;
            try {
                List<String> keywords = objectMapper.readValue(kp.getKeywords(), STRING_LIST_TYPE);
                for (String kw : keywords) {
                    if (kw.contains(tag) || tag.contains(kw)) return kp.getName();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private Map<String, List<String>> buildKeywordIndex(List<CourseKnowledgePoint> kps) {
        Map<String, List<String>> index = new HashMap<>();
        for (CourseKnowledgePoint kp : kps) {
            if (kp.getKeywords() == null) continue;
            try {
                List<String> keywords = objectMapper.readValue(kp.getKeywords(), STRING_LIST_TYPE);
                for (String kw : keywords) {
                    index.computeIfAbsent(kw, k -> new ArrayList<>()).add(kp.getName());
                }
            } catch (Exception ignored) {}
        }
        return index;
    }

    private List<String> getRecentWeakTags(String userId, String courseId, int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        LambdaQueryWrapper<QuizAttempt> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(QuizAttempt::getUserId, userId)
                .eq(QuizAttempt::getCourseId, courseId)
                .ge(QuizAttempt::getCreatedAt, since)
                .isNotNull(QuizAttempt::getWeakTags);
        List<QuizAttempt> attempts = quizAttemptMapper.selectList(wrapper);
        Set<String> tags = new LinkedHashSet<>();
        for (QuizAttempt a : attempts) {
            if (a.getWeakTags() == null) continue;
            try {
                List<String> parsed = objectMapper.readValue(a.getWeakTags(), STRING_LIST_TYPE);
                tags.addAll(parsed);
            } catch (Exception ignored) {}
        }
        return new ArrayList<>(tags);
    }

    // ==================== 理由收集 ====================

    private List<PushReason> collectReasons(double pathMatch, double weaknessMatch,
                                             double interestMatch,
                                             ResourcePack pack,
                                             List<ResourceItem> items,
                                             ScoringContext ctx) {
        List<PushReason> reasons = new ArrayList<>();
        String nextModuleName = ctx.nextModuleName != null ? ctx.nextModuleName : "";

        if (pathMatch > scoringProps.getReasonThresholdPathHigh() && !nextModuleName.isEmpty()) {
            reasons.add(PushReason.builder()
                    .dimension("path_match")
                    .label("即将进入的模块")
                    .detail("下一模块「" + nextModuleName + "」的预习资源")
                    .build());
        } else if (pathMatch > scoringProps.getReasonThresholdPathLow()) {
            reasons.add(PushReason.builder()
                    .dimension("path_match")
                    .label("当前学习模块")
                    .detail("当前模块中你还没学到的知识点")
                    .build());
        }
        if (weaknessMatch > scoringProps.getReasonThresholdWeakness()) {
            // 找出真正匹配的薄弱标签
            List<String> matched = ctx.weakTags.stream()
                    .filter(tag -> kpMatchesPackInMemory(tag, pack, items, ctx))
                    .limit(3)
                    .toList();
            String detail = matched.isEmpty()
                    ? pack.getTopic()
                    : String.join("、", matched);
            reasons.add(PushReason.builder()
                    .dimension("weakness_match")
                    .label("薄弱知识点")
                    .detail("覆盖了你的薄弱环节：" + detail)
                    .build());
        }
        if (interestMatch > scoringProps.getReasonThresholdInterest()) {
            // 找出真正匹配的兴趣标签
            List<String> matched = ctx.interestTags.stream()
                    .filter(tag -> kpMatchesTopicInMemory(tag, pack, ctx))
                    .limit(3)
                    .toList();
            String detail = matched.isEmpty()
                    ? "与你感兴趣的主题相关"
                    : "与你感兴趣的「" + String.join("、", matched) + "」相关";
            reasons.add(PushReason.builder()
                    .dimension("interest_match")
                    .label("兴趣方向")
                    .detail(detail)
                    .build());
        }
        if (reasons.isEmpty()) {
            reasons.add(PushReason.builder()
                    .dimension("general")
                    .label("综合推荐")
                    .detail("基于你的学习画像综合评估推荐")
                    .build());
        }

        return reasons;
    }

    // ==================== 排序比较器 ====================

    private int compareByPriority(ScoredPack a, ScoredPack b) {
        int cmp = Double.compare(b.getPathMatch(), a.getPathMatch());
        if (cmp != 0) return cmp;
        cmp = Double.compare(b.getWeaknessMatch(), a.getWeaknessMatch());
        if (cmp != 0) return cmp;
        cmp = Double.compare(b.getInterestMatch(), a.getInterestMatch());
        if (cmp != 0) return cmp;
        if (a.getCreatedAt() == null) return 1;
        if (b.getCreatedAt() == null) return -1;
        return b.getCreatedAt().compareTo(a.getCreatedAt());
    }

    // ==================== 辅助方法 ====================

    private List<Map<String, Object>> parsePlanModules(LearningPlan plan) {
        try {
            Map<String, Object> planObj = objectMapper.readValue(plan.getPlanJson(), MAP_TYPE);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> modules = (List<Map<String, Object>>) planObj.get("modules");
            return modules;
        } catch (Exception e) {
            log.warn("Failed to parse plan JSON: planId={}", plan.getId(), e);
            return List.of();
        }
    }

    private int findCurrentModuleIndex(List<Map<String, Object>> modules) {
        for (int i = 0; i < modules.size(); i++) {
            if ("in_progress".equals(modules.get(i).get("status"))) return i;
        }
        for (int i = 0; i < modules.size(); i++) {
            if ("ready".equals(modules.get(i).get("status"))) return i - 1;
        }
        return -1;
    }

    private List<String> extractUnlearnedKps(SubPlan sp) {
        try {
            Map<String, Object> spObj = objectMapper.readValue(sp.getSubPlanJson(), MAP_TYPE);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> activities = (List<Map<String, Object>>) spObj.get("activities");
            if (activities == null) return List.of();

            return activities.stream()
                    .filter(a -> !"completed".equals(a.get("status")))
                    .flatMap(a -> {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> kps = (List<Map<String, Object>>) a.get("knowledge_points");
                        if (kps == null) return java.util.stream.Stream.empty();
                        return kps.stream().map(kp -> (String) kp.get("name")).filter(Objects::nonNull);
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to parse sub_plan for KP extraction: {}", sp.getId(), e);
            return List.of();
        }
    }

    private ScoredPack buildScoredPackInMemory(ResourcePack pack, List<ResourceItem> items,
                                                double pathMatch, double weakness, double interest,
                                                List<PushReason> reasons) {
        String confidence = "medium";
        if (!items.isEmpty()) {
            long highCount = items.stream().filter(i -> "high".equalsIgnoreCase(i.getConfidence())).count();
            if (highCount > items.size() / 2) confidence = "high";
            else if (items.stream().anyMatch(i -> "low".equalsIgnoreCase(i.getConfidence()))) confidence = "low";
        }

        int estimatedMinutes = items.stream()
                .mapToInt(i -> {
                    try {
                        if (i.getMetadataJson() != null) {
                            Map<String, Object> meta = objectMapper.readValue(i.getMetadataJson(), MAP_TYPE);
                            Object em = meta.get("estimatedMinutes");
                            if (em instanceof Number n) return n.intValue();
                        }
                    } catch (Exception ignored) {}
                    return 15;
                })
                .sum();
        if (estimatedMinutes == 0) estimatedMinutes = 15;

        return ScoredPack.builder()
                .packId(pack.getId())
                .title(pack.getTopic())
                .knowledgePoint(pack.getTopic())
                .pathMatch(pathMatch)
                .weaknessMatch(weakness)
                .interestMatch(interest)
                .confidence(confidence)
                .estimatedMinutes(estimatedMinutes)
                .resourceCount(items.size())
                .reasons(reasons)
                .createdAt(pack.getCreatedAt() != null
                        ? pack.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null)
                .build();
    }
}
