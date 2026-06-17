package com.learnthink.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.push.PushReason;
import com.learnthink.common.dto.push.ScoredPack;
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
 * 推送评分器 — 基于多级优先级排序的候选资源包评分与排序
 *
 * <p>排序规则：path_match DESC → weakness_match DESC → interest_match DESC → created_at DESC</p>
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

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    /**
     * 对候选资源包评分并返回 Top-N
     */
    public List<ScoredPack> scoreCandidates(String userId, String courseId, int limit) {
        // 1. 获取候选池（已推送过的排除）
        List<ResourcePack> candidates = getFilteredCandidates(userId, courseId);
        if (candidates.isEmpty()) return List.of();

        // 2. 获取当前学习路径模块名（用于推送理由）
        String[] moduleNames = resolveModuleNames(userId, courseId);
        String currentModuleName = moduleNames[0];
        String nextModuleName = moduleNames[1];

        // 3. 对每个候选包计算各因子
        List<ScoredPack> scored = new ArrayList<>();
        for (ResourcePack pack : candidates) {
            double pathMatch = calcPathMatch(pack.getId(), userId, courseId);
            double weakness = calcWeaknessMatch(pack.getId(), userId, courseId);
            double interest = calcInterestMatch(pack.getId(), userId, courseId);

            List<PushReason> reasons = collectReasons(pathMatch, weakness, interest,
                    pack, currentModuleName, nextModuleName);

            scored.add(buildScoredPack(pack, pathMatch, weakness, interest, reasons));
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

        double pathMatch = calcPathMatch(packId, userId, courseId);
        double weakness = calcWeaknessMatch(packId, userId, courseId);
        double interest = calcInterestMatch(packId, userId, courseId);

        String[] moduleNames = resolveModuleNames(userId, courseId);
        List<PushReason> reasons = collectReasons(pathMatch, weakness, interest,
                pack, moduleNames[0], moduleNames[1]);

        return buildScoredPack(pack, pathMatch, weakness, interest, reasons);
    }

    // ==================== 候选池过滤 ====================

    private List<ResourcePack> getFilteredCandidates(String userId, String courseId) {
        // 查已推送过的 pack ID
        List<String> pushedPackIds = notificationMapper.selectList(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, userId)
                        .likeRight(Notification::getType, "push_")
                        .eq(Notification::getRefType, "pack")
                        .select(Notification::getRefId)
        ).stream().map(Notification::getRefId).filter(Objects::nonNull).distinct().toList();

        LambdaQueryWrapper<ResourcePack> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ResourcePack::getUserId, userId)
                .eq(ResourcePack::getCourseId, courseId)
                .isNull(ResourcePack::getDeletedAt);
        if (!pushedPackIds.isEmpty()) {
            wrapper.notIn(ResourcePack::getId, pushedPackIds);
        }
        wrapper.orderByDesc(ResourcePack::getCreatedAt);

        return resourcePackMapper.selectList(wrapper);
    }

    // ==================== 路径匹配 (path_match) ====================

    double calcPathMatch(String packId, String userId, String courseId) {
        LearningPlan plan = learningPlanMapper.findByUserIdAndCourseId(userId, courseId);
        if (plan == null) return 0.0;

        List<Map<String, Object>> modules = parsePlanModules(plan);
        if (modules == null || modules.isEmpty()) return 0.0;

        int currentIdx = findCurrentModuleIndex(modules);
        if (currentIdx == -1) return 0.0;

        // 第一目标：下一模块
        int nextIdx = currentIdx + 1;
        if (nextIdx < modules.size()) {
            Map<String, Object> nextModule = modules.get(nextIdx);
            double score = matchPackAgainstModule(packId, nextModule);
            if (score > 0) return score;
        }

        // 第二目标：当前模块未完成活动的知识点
        Map<String, Object> currentModule = modules.get(currentIdx);
        String subPlanId = (String) currentModule.get("sub_plan_id");
        if (subPlanId == null) return 0.0;

        SubPlan sp = subPlanMapper.selectById(subPlanId);
        if (sp == null) return 0.0;

        List<String> unlearnedKps = extractUnlearnedKps(sp);
        if (unlearnedKps.isEmpty()) return 0.0;

        ResourcePack pack = resourcePackMapper.selectById(packId);
        if (pack == null) return 0.0;

        long matchCount = unlearnedKps.stream()
                .filter(kpName -> kpMatchesPack(kpName, pack))
                .count();

        return Math.min(1.0, matchCount / (double) unlearnedKps.size() + 0.2);
    }

    private double matchPackAgainstModule(String packId, Map<String, Object> module) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> kps = (List<Map<String, Object>>) module.get("knowledge_points");
        if (kps == null || kps.isEmpty()) return 0.0;

        List<String> moduleKpNames = kps.stream()
                .map(kp -> (String) kp.get("name"))
                .filter(Objects::nonNull)
                .toList();
        if (moduleKpNames.isEmpty()) return 0.0;

        ResourcePack pack = resourcePackMapper.selectById(packId);
        if (pack == null) return 0.0;

        long matchCount = moduleKpNames.stream()
                .filter(kp -> kpMatchesPack(kp, pack))
                .count();

        return matchCount > 0 ? Math.min(1.0, matchCount / (double) moduleKpNames.size() + 0.3) : 0.0;
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

    // ==================== 薄弱知识点匹配 (weakness_match) ====================

    double calcWeaknessMatch(String packId, String userId, String courseId) {
        Set<String> allWeakTags = new HashSet<>();

        // 1. 从画像 KP anchors 获取薄弱标签
        LambdaQueryWrapper<ProfileVersion> pvWrapper = new LambdaQueryWrapper<>();
        pvWrapper.eq(ProfileVersion::getUserId, userId)
                .eq(ProfileVersion::getCourseId, courseId)
                .orderByDesc(ProfileVersion::getCreatedAt)
                .last("LIMIT 1");
        ProfileVersion profile = profileVersionMapper.selectOne(pvWrapper);
        if (profile != null) {
            List<ProfileKpAnchor> weakAnchors = profileKpAnchorMapper.selectList(
                    new LambdaQueryWrapper<ProfileKpAnchor>()
                            .eq(ProfileKpAnchor::getProfileVersionId, profile.getId())
                            .eq(ProfileKpAnchor::getRelationType, "weak"));
            for (ProfileKpAnchor anchor : weakAnchors) {
                String kpName = getKpNameById(anchor.getKpId());
                if (kpName != null) allWeakTags.add(kpName);
            }
        }

        // 2. 从最近 quiz 获取薄弱标签
        List<String> recentWeakTags = getRecentWeakTags(userId, courseId, 7);
        for (String tag : recentWeakTags) {
            String kpName = fuzzyMatchKpName(tag, courseId);
            if (kpName != null) allWeakTags.add(kpName);
            else allWeakTags.add(tag); // fallback: 使用原始标签文本
        }

        if (allWeakTags.isEmpty()) return 0.0;

        ResourcePack pack = resourcePackMapper.selectById(packId);
        if (pack == null) return 0.0;

        String packTopic = pack.getTopic() != null ? pack.getTopic() : "";
        List<ResourceItem> items = resourceItemMapper.selectList(
                new LambdaQueryWrapper<ResourceItem>()
                        .eq(ResourceItem::getPackId, packId)
                        .isNull(ResourceItem::getDeletedAt));

        long matchCount = allWeakTags.stream()
                .filter(kpName -> {
                    if (packTopic.contains(kpName)) return true;
                    for (ResourceItem item : items) {
                        if (item.getTitle() != null && item.getTitle().contains(kpName)) return true;
                    }
                    List<String> keywords = getKeywordsByName(kpName);
                    for (String kw : keywords) {
                        if (packTopic.contains(kw)) return true;
                    }
                    return false;
                })
                .count();

        if (matchCount == 0) return 0.0;
        double ratio = matchCount / (double) allWeakTags.size();
        return Math.min(1.0, ratio + 0.3);
    }

    // ==================== 兴趣匹配 (interest_match) ====================

    double calcInterestMatch(String packId, String userId, String courseId) {
        LambdaQueryWrapper<ProfileVersion> pvWrapper = new LambdaQueryWrapper<>();
        pvWrapper.eq(ProfileVersion::getUserId, userId)
                .eq(ProfileVersion::getCourseId, courseId)
                .orderByDesc(ProfileVersion::getCreatedAt)
                .last("LIMIT 1");
        ProfileVersion profile = profileVersionMapper.selectOne(pvWrapper);
        if (profile == null) return 0.0;

        List<ProfileKpAnchor> interestAnchors = profileKpAnchorMapper.selectList(
                new LambdaQueryWrapper<ProfileKpAnchor>()
                        .eq(ProfileKpAnchor::getProfileVersionId, profile.getId())
                        .eq(ProfileKpAnchor::getRelationType, "interest"));
        List<String> interestTags = interestAnchors.stream()
                .map(a -> getKpNameById(a.getKpId()))
                .filter(Objects::nonNull)
                .toList();

        if (interestTags.isEmpty()) return 0.0;

        ResourcePack pack = resourcePackMapper.selectById(packId);
        if (pack == null) return 0.0;

        String packTopic = pack.getTopic() != null ? pack.getTopic() : "";
        long matchCount = interestTags.stream()
                .filter(kpName -> {
                    if (packTopic.contains(kpName)) return true;
                    List<String> keywords = getKeywordsByName(kpName);
                    for (String kw : keywords) {
                        if (packTopic.contains(kw)) return true;
                    }
                    return false;
                })
                .count();

        if (matchCount == 0) return 0.0;
        return Math.min(1.0, matchCount / Math.max(1, interestTags.size()) + 0.2);
    }

    // ==================== KP 名称 / 关键词查询 ====================

    boolean kpMatchesPack(String kpName, ResourcePack pack) {
        if (pack.getTopic() != null && pack.getTopic().contains(kpName)) return true;
        List<ResourceItem> items = resourceItemMapper.selectList(
                new LambdaQueryWrapper<ResourceItem>()
                        .eq(ResourceItem::getPackId, pack.getId())
                        .isNull(ResourceItem::getDeletedAt));
        for (ResourceItem item : items) {
            if (item.getTitle() != null && item.getTitle().contains(kpName)) return true;
        }
        List<String> keywords = getKeywordsByName(kpName);
        for (String kw : keywords) {
            if (pack.getTopic() != null && pack.getTopic().contains(kw)) return true;
        }
        return false;
    }

    private String getKpNameById(String kpId) {
        if (kpId == null) return null;
        CourseKnowledgePoint kp = kpMapper.selectById(kpId);
        return kp != null ? kp.getName() : null;
    }

    private List<String> getKeywordsByName(String kpName) {
        CourseKnowledgePoint kp = kpMapper.selectOne(
                new LambdaQueryWrapper<CourseKnowledgePoint>()
                        .eq(CourseKnowledgePoint::getName, kpName)
                        .last("LIMIT 1"));
        if (kp == null || kp.getKeywords() == null) return List.of();
        try {
            return objectMapper.readValue(kp.getKeywords(), STRING_LIST_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }

    String fuzzyMatchKpName(String tag, String courseId) {
        if (tag == null || tag.isBlank()) return null;
        // 精确 name 匹配
        CourseKnowledgePoint kp = kpMapper.selectOne(
                new LambdaQueryWrapper<CourseKnowledgePoint>()
                        .eq(CourseKnowledgePoint::getCourseId, courseId)
                        .eq(CourseKnowledgePoint::getName, tag)
                        .last("LIMIT 1"));
        if (kp != null) return kp.getName();
        // keywords LIKE 匹配
        List<CourseKnowledgePoint> allKps = kpMapper.selectList(
                new LambdaQueryWrapper<CourseKnowledgePoint>()
                        .eq(CourseKnowledgePoint::getCourseId, courseId)
                        .isNotNull(CourseKnowledgePoint::getKeywords));
        for (CourseKnowledgePoint candidate : allKps) {
            if (candidate.getKeywords() == null) continue;
            try {
                List<String> keywords = objectMapper.readValue(candidate.getKeywords(), STRING_LIST_TYPE);
                for (String kw : keywords) {
                    if (kw.contains(tag) || tag.contains(kw)) return candidate.getName();
                }
            } catch (Exception ignored) {}
        }
        return null;
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

    List<PushReason> collectReasons(double pathMatch, double weaknessMatch,
                                     double interestMatch,
                                     ResourcePack pack,
                                     String currentModuleName,
                                     String nextModuleName) {
        List<PushReason> reasons = new ArrayList<>();

        if (pathMatch > 0.5 && !nextModuleName.isEmpty()) {
            reasons.add(PushReason.builder()
                    .dimension("path_match")
                    .label("即将进入的模块")
                    .detail("下一模块「" + nextModuleName + "」的预习资源")
                    .build());
        } else if (pathMatch > 0.3) {
            reasons.add(PushReason.builder()
                    .dimension("path_match")
                    .label("当前学习模块")
                    .detail("当前模块中你还没学到的知识点")
                    .build());
        }
        if (weaknessMatch > 0.3) {
            String matchedTags = findMatchedWeakTags(pack);
            reasons.add(PushReason.builder()
                    .dimension("weakness_match")
                    .label("薄弱知识点")
                    .detail("覆盖了你的薄弱环节：" + (matchedTags != null ? matchedTags : pack.getTopic()))
                    .build());
        }
        if (interestMatch > 0.3) {
            reasons.add(PushReason.builder()
                    .dimension("interest_match")
                    .label("兴趣方向")
                    .detail("与你感兴趣的主题相关")
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

    private String findMatchedWeakTags(ResourcePack pack) {
        // 简化版：返回 pack topic 的截断
        if (pack.getTopic() == null) return null;
        return pack.getTopic().length() > 30 ? pack.getTopic().substring(0, 30) + "..." : pack.getTopic();
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

    private String[] resolveModuleNames(String userId, String courseId) {
        String current = "";
        String next = "";
        LearningPlan plan = learningPlanMapper.findByUserIdAndCourseId(userId, courseId);
        if (plan != null) {
            List<Map<String, Object>> modules = parsePlanModules(plan);
            if (modules != null) {
                int idx = findCurrentModuleIndex(modules);
                if (idx >= 0 && idx < modules.size()) {
                    current = (String) modules.get(idx).get("title");
                    if (idx + 1 < modules.size()) {
                        next = (String) modules.get(idx + 1).get("title");
                    }
                }
            }
        }
        return new String[]{current != null ? current : "", next != null ? next : ""};
    }

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
        // fallback: 第一个 ready 模块的前一个
        for (int i = 0; i < modules.size(); i++) {
            if ("ready".equals(modules.get(i).get("status"))) return i - 1;
        }
        return -1;
    }

    private ScoredPack buildScoredPack(ResourcePack pack, double pathMatch,
                                        double weakness, double interest,
                                        List<PushReason> reasons) {
        List<ResourceItem> items = resourceItemMapper.selectList(
                new LambdaQueryWrapper<ResourceItem>()
                        .eq(ResourceItem::getPackId, pack.getId())
                        .isNull(ResourceItem::getDeletedAt));

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
                        ? pack.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null)
                .build();
    }
}
