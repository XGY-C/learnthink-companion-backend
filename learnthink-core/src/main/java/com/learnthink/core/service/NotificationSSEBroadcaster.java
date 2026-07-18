package com.learnthink.core.service;

import com.learnthink.common.dto.user.NotificationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 通知 SSE 广播器 - 管理 per-user 的 SSE 连接，实时推送通知
 *
 * <p>参考 {@code DefaultTaskEventBroadcaster} 的模式，但按 userId 而非 taskId 分组。</p>
 */
@Slf4j
@Component
public class NotificationSSEBroadcaster {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    /**
     * 订阅用户的通知 SSE 流
     *
     * @param userId 用户 ID
     * @return SseEmitter
     */
    public SseEmitter subscribe(String userId) {
        // 超时 0L = 永不超时（由心跳和客户端断开管理生命周期）
        SseEmitter emitter = new SseEmitter(0L);
        subscribers.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeSubscriber(userId, emitter));
        emitter.onTimeout(() -> removeSubscriber(userId, emitter));
        emitter.onError(e -> removeSubscriber(userId, emitter));

        // 发送初始连接事件，让前端确认连接成功
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("status", "connected")));
        } catch (IOException e) {
            removeSubscriber(userId, emitter);
        }

        return emitter;
    }

    /**
     * 向指定用户广播一条通知事件
     *
     * @param userId       用户 ID
     * @param notification 通知响应 DTO
     */
    public void broadcastNotification(String userId, NotificationResponse notification) {
        broadcast(userId, "push", Map.of(
                "notificationId", notification.getId(),
                "type", notification.getType(),
                "title", notification.getTitle(),
                "message", notification.getMessage(),
                "refId", notification.getRefId() != null ? notification.getRefId() : "",
                "refType", notification.getRefType() != null ? notification.getRefType() : "",
                "isRead", notification.isRead(),
                "createdAt", notification.getCreatedAt() != null ? notification.getCreatedAt() : ""
        ));
    }

    /**
     * 向指定用户广播未读数量更新
     */
    public void broadcastUnreadCount(String userId, int unreadCount) {
        broadcast(userId, "unread-count", Map.of("unreadCount", unreadCount));
    }

    /**
     * 获取当前在线用户 ID 集合（有活跃 SSE 连接的用户）
     */
    public Set<String> getOnlineUserIds() {
        return subscribers.keySet();
    }

    private void broadcast(String userId, String eventType, Map<String, Object> payload) {
        List<SseEmitter> emitters = subscribers.get(userId);
        if (emitters == null || emitters.isEmpty()) return;

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventType).data(payload));
            } catch (Exception e) {
                log.debug("SSE send failed for user {} (removed): {}", userId, e.getMessage());
                removeSubscriber(userId, emitter);
            }
        }
    }

    private void removeSubscriber(String userId, SseEmitter emitter) {
        List<SseEmitter> emitters = subscribers.get(userId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) subscribers.remove(userId);
        }
    }
}
