package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.push.PushReason;
import com.learnthink.common.dto.push.ScoredPack;
import com.learnthink.core.domain.entity.Notification;
import com.learnthink.core.domain.entity.ResourcePack;
import com.learnthink.core.repository.NotificationMapper;
import com.learnthink.core.repository.ResourcePackMapper;
import com.learnthink.core.service.PushScorer;
import com.learnthink.core.service.PushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

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
    private final ObjectMapper objectMapper;

    @Value("${learnthink.push.cooldown-minutes:120}")
    private int cooldownMinutes;

    @Value("${learnthink.push.recommendation-limit:5}")
    private int recommendationLimit;

    @Override
    public List<ScoredPack> getRecommendations(String userId, String courseId, int limit) {
        int effectiveLimit = limit > 0 ? limit : recommendationLimit;
        return pushScorer.scoreCandidates(userId, courseId, effectiveLimit);
    }

    @Override
    @Transactional
    public void notifyResourceReady(String userId, String courseId, String packId,
                                    String pushType, List<PushReason> reasons) {
        ResourcePack pack = resourcePackMapper.selectById(packId);
        if (pack == null) {
            log.warn("Resource pack not found for push: packId={}", packId);
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

        // 写入通知
        Notification notif = new Notification();
        notif.setUserId(userId);
        notif.setType(pushType);
        notif.setTitle(buildTitle(pushType));
        notif.setMessage("「" + pack.getTopic() + "」资源包已生成，点击查看");
        notif.setRefId(packId);
        notif.setRefType("pack");
        notif.setIsRead(false);
        notif.setIsPushed(shouldPush);
        notif.setPayloadJson(payloadJson);

        if (shouldPush) {
            notif.setPushedAt(LocalDateTime.now());
        }

        notificationMapper.insert(notif);
        log.info("Push notification created: type={}, packId={}, pushed={}", pushType, packId, shouldPush);
    }

    @Override
    @Transactional
    public void notifyWeaknessFound(String userId, String courseId, String weakTag, String packId) {
        ResourcePack pack = resourcePackMapper.selectById(packId);
        if (pack == null) {
            log.warn("Resource pack not found for weakness push: packId={}", packId);
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

        Notification notif = new Notification();
        notif.setUserId(userId);
        notif.setType("push_weakness_found");
        notif.setTitle("薄弱项推荐");
        notif.setMessage(weakTag + " 练习资源包已就绪");
        notif.setRefId(packId);
        notif.setRefType("pack");
        notif.setIsRead(false);
        notif.setIsPushed(shouldPush);
        notif.setPayloadJson(payloadJson);

        if (shouldPush) {
            notif.setPushedAt(LocalDateTime.now());
        }

        notificationMapper.insert(notif);
        log.info("Weakness push notification created: weakTag={}, packId={}, pushed={}", weakTag, packId, shouldPush);
    }

    @Override
    public int getUnreadPushCount(String userId) {
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, false)
                .likeRight(Notification::getType, "push_");
        return Math.toIntExact(notificationMapper.selectCount(wrapper));
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
            return true; // 从未推送过，冷却已过
        }

        long elapsedMinutes = java.time.Duration.between(
                lastPushed.getPushedAt(), LocalDateTime.now()).toMinutes();
        boolean expired = elapsedMinutes >= cooldownMinutes;
        if (!expired) {
            log.info("Push cooldown active: lastPushed={}, elapsed={}min, cooldown={}min",
                    lastPushed.getPushedAt(), elapsedMinutes, cooldownMinutes);
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
}
