package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.learnthink.common.dto.push.PushReason;
import com.learnthink.common.dto.push.ScoredPack;
import com.learnthink.common.dto.user.NotificationResponse;
import com.learnthink.core.config.PushProperties;
import com.learnthink.core.config.PushScoringProperties;
import com.learnthink.core.domain.entity.Notification;
import com.learnthink.core.domain.entity.ResourcePack;
import com.learnthink.core.domain.entity.UserCourseEnrollment;
import com.learnthink.core.repository.NotificationMapper;
import com.learnthink.core.repository.ResourcePackMapper;
import com.learnthink.core.repository.UserCourseEnrollmentMapper;
import com.learnthink.core.service.NotificationSSEBroadcaster;
import com.learnthink.core.service.PushScorer;
import com.learnthink.core.service.PushService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 精准资源推送服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushServiceImpl implements PushService {

    private final PushScorer pushScorer;
    private final NotificationMapper notificationMapper;
    private final ResourcePackMapper resourcePackMapper;
    private final UserCourseEnrollmentMapper enrollmentMapper;
    private final ObjectMapper objectMapper;
    private final PushScoringProperties scoringProps;
    private final PushProperties pushProperties;
    private final NotificationSSEBroadcaster sseBroadcaster;

    /** 推荐结果缓存（key = userId:courseId） */
    private Cache<String, List<ScoredPack>> recommendationCache;

    @PostConstruct
    public void initCache() {
        recommendationCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(scoringProps.getCacheTtlMinutes()))
                .maximumSize(scoringProps.getCacheMaxSize())
                .build();
    }

    @Override
    public List<ScoredPack> getRecommendations(String userId, String courseId, int limit) {
        int effectiveLimit = limit > 0 ? limit : pushProperties.getRecommendationLimit();
        String cacheKey = userId + ":" + courseId;

        // 查缓存
        List<ScoredPack> cached = recommendationCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached.subList(0, Math.min(effectiveLimit, cached.size()));
        }

        // 缓存未命中，计算并缓存
        List<ScoredPack> scored = pushScorer.scoreCandidates(userId, courseId, 20);
        recommendationCache.put(cacheKey, scored);
        return scored.subList(0, Math.min(effectiveLimit, scored.size()));
    }

    @Override
    @Transactional
    public void notifyResourceReady(String userId, String courseId, String packId,
                                    String pushType, List<PushReason> reasons) {
        if (!pushProperties.isEnabled()) {
            log.debug("Push disabled, skipping notifyResourceReady");
            return;
        }

        // 失效缓存
        recommendationCache.invalidate(userId + ":" + courseId);

        ResourcePack pack = resourcePackMapper.selectById(packId);
        if (pack == null) {
            log.warn("Resource pack not found for push: packId={}", packId);
            return;
        }

        // 去重：同 packId + type 已有未读通知则跳过
        LambdaQueryWrapper<Notification> dupCheck = new LambdaQueryWrapper<>();
        dupCheck.eq(Notification::getUserId, userId)
                .eq(Notification::getRefId, packId)
                .eq(Notification::getType, pushType)
                .eq(Notification::getIsRead, false);
        if (notificationMapper.selectCount(dupCheck) > 0) {
            log.info("Duplicate push skipped: type={}, packId={}, userId={}", pushType, packId, userId);
            return;
        }

        // 评分
        ScoredPack scored = pushScorer.scoreSingle(packId, userId, courseId);
        double pathMatch = scored != null ? scored.getPathMatch() : 0;
        double weaknessMatch = scored != null ? scored.getWeaknessMatch() : 0;
        double interestMatch = scored != null ? scored.getInterestMatch() : 0;

        // 检查冷却
        boolean cooldownExpired = isCooldownExpired(userId);
        boolean shouldPush = cooldownExpired;

        // 构建 payload
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pathMatch", pathMatch);
        payload.put("weaknessMatch", weaknessMatch);
        payload.put("interestMatch", interestMatch);
        payload.put("reasons", reasons != null ? reasons : List.of());

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialize push payload", e);
            payloadJson = "{}";
        }

        // 写入通知（有 taskId 则跳转到资源工作室，否则跳转资源库）
        boolean hasTask = pack.getTaskId() != null;
        Notification notif = new Notification();
        notif.setUserId(userId);
        notif.setType(pushType);
        notif.setTitle(buildTitle(pushType));
        notif.setMessage("「" + pack.getTopic() + "」资源包已生成，点击查看");
        notif.setRefId(hasTask ? pack.getTaskId() : packId);
        notif.setRefType(hasTask ? "task" : "pack");
        notif.setIsRead(false);
        notif.setIsPushed(shouldPush);
        notif.setPayloadJson(payloadJson);

        if (shouldPush) {
            notif.setPushedAt(LocalDateTime.now());
        }
        notif.setCreatedAt(LocalDateTime.now());

        notificationMapper.insert(notif);
        log.info("Push notification created: type={}, packId={}, pushed={}", pushType, packId, shouldPush);

        // 通过 SSE 实时推送
        if (shouldPush) {
            sseBroadcaster.broadcastNotification(userId, toResponse(notif));
        }
    }

    @Override
    @Transactional
    public void notifyWeaknessFound(String userId, String courseId, String weakTag, String packId) {
        if (!pushProperties.isEnabled()) {
            log.debug("Push disabled, skipping notifyWeaknessFound");
            return;
        }

        // 失效缓存
        recommendationCache.invalidate(userId + ":" + courseId);

        ResourcePack pack = resourcePackMapper.selectById(packId);
        if (pack == null) {
            log.warn("Resource pack not found for weakness push: packId={}", packId);
            return;
        }

        // 去重：同 packId + type 已有未读通知则跳过
        LambdaQueryWrapper<Notification> dupCheck = new LambdaQueryWrapper<>();
        dupCheck.eq(Notification::getUserId, userId)
                .eq(Notification::getRefId, packId)
                .eq(Notification::getType, "push_weakness_found")
                .eq(Notification::getIsRead, false);
        if (notificationMapper.selectCount(dupCheck) > 0) {
            log.info("Duplicate weakness push skipped: packId={}, userId={}", packId, userId);
            return;
        }

        ScoredPack scored = pushScorer.scoreSingle(packId, userId, courseId);
        double pathMatch = scored != null ? scored.getPathMatch() : 0;
        double weaknessMatch = scored != null ? scored.getWeaknessMatch() : 0;
        double interestMatch = scored != null ? scored.getInterestMatch() : 0;

        boolean cooldownExpired = isCooldownExpired(userId);
        boolean shouldPush = cooldownExpired;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pathMatch", pathMatch);
        payload.put("weaknessMatch", weaknessMatch);
        payload.put("interestMatch", interestMatch);
        payload.put("weakTag", weakTag);
        payload.put("reasons", List.of(
                PushReason.builder()
                        .dimension("weakness_match")
                        .label("薄弱知识点")
                        .detail("针对薄弱点「" + weakTag + "」的练习资源")
                        .build()));

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialize push payload", e);
            payloadJson = "{}";
        }

        // 写入通知（有 taskId 则跳转到资源工作室，否则跳转资源库）
        boolean hasTask = pack.getTaskId() != null;
        Notification notif = new Notification();
        notif.setUserId(userId);
        notif.setType("push_weakness_found");
        notif.setTitle("薄弱项推荐");
        notif.setMessage(weakTag + " 练习资源包已就绪");
        notif.setRefId(hasTask ? pack.getTaskId() : packId);
        notif.setRefType(hasTask ? "task" : "pack");
        notif.setIsRead(false);
        notif.setIsPushed(shouldPush);
        notif.setPayloadJson(payloadJson);

        if (shouldPush) {
            notif.setPushedAt(LocalDateTime.now());
        }
        notif.setCreatedAt(LocalDateTime.now());

        notificationMapper.insert(notif);
        log.info("Weakness push notification created: weakTag={}, packId={}, pushed={}", weakTag, packId, shouldPush);

        // 通过 SSE 实时推送
        if (shouldPush) {
            sseBroadcaster.broadcastNotification(userId, toResponse(notif));
        }
    }

    @Override
    public int getUnreadPushCount(String userId) {
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, false)
                .eq(Notification::getIsPushed, true)
                .likeRight(Notification::getType, "push_");
        return Math.toIntExact(notificationMapper.selectCount(wrapper));
    }

    // ==================== 每日定时推荐推送 ====================

    /**
     * 每日定时推送学习资源推荐 — 每天 08:00 和 15:00 执行。
     * 根据学生的画像、学习路径、薄弱点，自动挑选 Top-N 推荐资源，
     * 通过 SSE 推送给当前在线用户。
     */
    @Scheduled(cron = "0 0 8,15 * * *", zone = "Asia/Shanghai")
    public void scheduledDailyPush() {
        if (!pushProperties.isEnabled()) {
            log.debug("Push disabled, skipping scheduled daily push");
            return;
        }

        Set<String> onlineUsers = sseBroadcaster.getOnlineUserIds();
        if (onlineUsers.isEmpty()) {
            log.debug("Scheduled daily push: no online users");
            return;
        }

        int totalPushed = 0;
        for (String userId : onlineUsers) {
            try {
                // 查找用户已选课程
                List<UserCourseEnrollment> enrollments = enrollmentMapper.selectList(
                        new LambdaQueryWrapper<UserCourseEnrollment>()
                                .eq(UserCourseEnrollment::getUserId, userId));
                if (enrollments.isEmpty()) continue;

                String courseId = enrollments.get(0).getCourseId();

                // 获取 Top-3 推荐
                List<ScoredPack> top = pushScorer.scoreCandidates(userId, courseId, 3);
                if (top.isEmpty()) continue;

                // 构建概览
                StringJoiner joiner = new StringJoiner("、");
                for (ScoredPack sp : top) {
                    joiner.add(sp.getTitle());
                }

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("count", top.size());
                payload.put("resources", top.stream()
                        .map(sp -> Map.of(
                                "packId", sp.getPackId(),
                                "title", sp.getTitle(),
                                "knowledgePoint", sp.getKnowledgePoint(),
                                "pathMatch", sp.getPathMatch(),
                                "weaknessMatch", sp.getWeaknessMatch(),
                                "interestMatch", sp.getInterestMatch(),
                                "confidence", sp.getConfidence(),
                                "estimatedMinutes", sp.getEstimatedMinutes()
                        ))
                        .collect(Collectors.toList()));

                String payloadJson;
                try {
                    payloadJson = objectMapper.writeValueAsString(payload);
                } catch (Exception e) {
                    log.warn("Failed to serialize daily push payload for user {}", userId);
                    payloadJson = "{}";
                }

                Notification notif = new Notification();
                notif.setUserId(userId);
                notif.setType("daily_recommendation");
                notif.setTitle("今日学习推荐");
                notif.setMessage("为你推荐 " + top.size() + " 个学习资源：" + joiner);
                notif.setRefId(null);
                notif.setRefType("daily");
                notif.setIsRead(false);
                notif.setIsPushed(true);
                notif.setPushedAt(LocalDateTime.now());
                notif.setPayloadJson(payloadJson);
                notif.setCreatedAt(LocalDateTime.now());

                notificationMapper.insert(notif);
                sseBroadcaster.broadcastNotification(userId, toResponse(notif));

                // 更新未读计数
                int unread = Math.toIntExact(notificationMapper.selectCount(
                        new LambdaQueryWrapper<Notification>()
                                .eq(Notification::getUserId, userId)
                                .eq(Notification::getIsRead, false)
                                .likeRight(Notification::getType, "push_")));
                sseBroadcaster.broadcastUnreadCount(userId, unread);
                totalPushed++;

                log.info("Daily push sent to user {}: {} recommendations", userId, top.size());
            } catch (Exception e) {
                log.warn("Scheduled daily push failed for user {}: {}", userId, e.getMessage());
            }
        }

        log.info("Scheduled daily push done: {} users received recommendations", totalPushed);
    }

    // ==================== 冷却检查 ====================

    private boolean isCooldownExpired(String userId) {
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Notification::getUserId, userId)
                .likeRight(Notification::getType, "push_")
                .eq(Notification::getIsPushed, true)
                .isNotNull(Notification::getPushedAt)
                .orderByDesc(Notification::getPushedAt)
                .last("LIMIT 1");
        Notification lastPushed = notificationMapper.selectOne(wrapper);

        if (lastPushed == null || lastPushed.getPushedAt() == null) {
            return true;
        }

        long elapsedMinutes = java.time.Duration.between(
                lastPushed.getPushedAt(), LocalDateTime.now()).toMinutes();
        boolean expired = elapsedMinutes >= pushProperties.getCooldownMinutes();
        if (!expired) {
            log.info("Push cooldown active: lastPushed={}, elapsed={}min, cooldown={}min",
                    lastPushed.getPushedAt(), elapsedMinutes, pushProperties.getCooldownMinutes());
        }
        return expired;
    }

    private String buildTitle(String pushType) {
        return switch (pushType) {
            case "push_resource_ready" -> "新资源就绪";
            case "push_path_next" -> "学习路径推荐";
            case "push_weakness_found" -> "薄弱项推荐";
            default -> "资源推荐";
        };
    }

    private NotificationResponse toResponse(Notification n) {
        return NotificationResponse.builder()
                .id(n.getId())
                .type(n.getType())
                .title(n.getTitle())
                .message(n.getMessage())
                .isRead(n.getIsRead() != null ? n.getIsRead() : false)
                .isPushed(n.getIsPushed() != null ? n.getIsPushed() : false)
                .refId(n.getRefId())
                .refType(n.getRefType())
.createdAt(n.getCreatedAt() != null
        ? n.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toString()
        : null)
                .build();
    }
}
