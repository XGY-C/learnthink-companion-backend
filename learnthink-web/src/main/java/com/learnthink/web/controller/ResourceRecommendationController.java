package com.learnthink.web.controller;

import com.learnthink.common.dto.push.RecommendationResponse;
import com.learnthink.common.dto.push.ScoredPack;
import com.learnthink.common.dto.user.NotificationResponse;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.service.NotificationService;
import com.learnthink.core.service.PushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 精准资源推荐控制器
 */
@Slf4j
@RestController
@RequestMapping("/resources/recommendations")
@RequiredArgsConstructor
public class ResourceRecommendationController {

    private final PushService pushService;
    private final NotificationService notificationService;
    private final com.learnthink.core.service.RecommendationFeedbackService feedbackService;

    /**
     * Dashboard 主动查看推荐
     */
    @GetMapping
    public Result<RecommendationResponse> getRecommendations(
            @RequestParam("course_id") String courseId,
            @RequestParam(value = "limit", defaultValue = "5") int limit) {
        String userId = UserContextUtil.getCurrentUserId();
        int safeLimit = Math.min(Math.max(limit, 1), 20);
        List<ScoredPack> scored = pushService.getRecommendations(userId, courseId, safeLimit);

        RecommendationResponse resp = RecommendationResponse.builder()
                .main(scored.isEmpty() ? null : scored.get(0))
                .secondary(scored.size() > 1 ? scored.subList(1, scored.size()) : List.of())
                .build();

        return Result.success(resp);
    }

    /**
     * 获取通知列表（全部类型，含推送和非推送）
     */
    @GetMapping("/notifications")
    public Result<Map<String, Object>> getNotifications(
            @RequestParam("course_id") String courseId,
            @RequestParam(value = "type", defaultValue = "all") String typeFilter) {
        String userId = UserContextUtil.getCurrentUserId();

        List<NotificationResponse> allNotifs = notificationService.getUserNotifications(
                userId, true, 1, 50);
        List<NotificationResponse> filtered;
        if ("push".equals(typeFilter)) {
            filtered = allNotifs.stream()
                    .filter(n -> n.getType() != null && n.getType().startsWith("push_"))
                    .toList();
        } else {
            filtered = allNotifs;
        }

        // 从返回列表中统计未读数，确保与红点一致
        int unreadCount = (int) filtered.stream().filter(n -> !n.isRead()).count();

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("notifications", filtered);
        data.put("unreadCount", unreadCount);
        return Result.success(data);
    }

    /**
     * 获取未读推送通知数量（仅用于铃铛红点）
     */
    @GetMapping("/notifications/unread-count")
    public Result<Map<String, Object>> getUnreadCount() {
        String userId = UserContextUtil.getCurrentUserId();
        int count = pushService.getUnreadPushCount(userId);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("unreadCount", count);
        return Result.success(data);
    }

    /**
     * 标记单条推送通知已读
     */
    @PutMapping("/notifications/{id}/read")
    public Result<Void> markAsRead(@PathVariable String id) {
        String userId = UserContextUtil.getCurrentUserId();
        notificationService.markAsRead(id, userId);
        return Result.success();
    }

    /**
     * 全部标为已读
     */
    @PutMapping("/notifications/read-all")
    public Result<Void> markAllAsRead() {
        String userId = UserContextUtil.getCurrentUserId();
        notificationService.markAllAsRead(userId);
        return Result.success();
    }

    /**
     * 删除单条通知
     */
    @DeleteMapping("/notifications/{id}")
    public Result<Void> deleteNotification(@PathVariable String id) {
        String userId = UserContextUtil.getCurrentUserId();
        notificationService.deleteNotification(id, userId);
        return Result.success();
    }

    /**
     * 记录推荐反馈行为（前端埋点）
     */
    @PostMapping("/feedback")
    public Result<Void> recordFeedback(@RequestBody FeedbackRequest req) {
        String userId = UserContextUtil.getCurrentUserId();
        feedbackService.record(userId, req.getCourseId(), req.getPackId(),
                req.getAction(), req.getSource(), req.getNotificationId(), req.getScoreJson());
        return Result.success();
    }

    /**
     * 推荐效果统计
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats(
            @RequestParam("course_id") String courseId,
            @RequestParam(value = "days", defaultValue = "30") int days) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(feedbackService.getStats(userId, courseId, days));
    }

    /**
     * 反馈请求体（兼容前端 snake_case）
     */
    @lombok.Data
    @com.fasterxml.jackson.databind.annotation.JsonNaming(
            com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class FeedbackRequest {
        private String courseId;
        private String packId;
        private String action;
        private String source;
        private String notificationId;
        private String scoreJson;
    }
}
