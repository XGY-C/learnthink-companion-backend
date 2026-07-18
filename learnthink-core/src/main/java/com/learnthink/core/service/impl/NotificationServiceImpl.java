package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.learnthink.common.dto.user.NotificationResponse;
import com.learnthink.core.domain.entity.Notification;
import com.learnthink.core.repository.NotificationMapper;
import com.learnthink.core.service.NotificationService;
import com.learnthink.core.service.NotificationSSEBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationMapper notificationMapper;
    private final NotificationSSEBroadcaster sseBroadcaster;

    @Override
    public List<NotificationResponse> getUserNotifications(String userId, boolean includeUnpushed, int page, int size) {
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Notification::getUserId, userId);
        if (!includeUnpushed) {
            wrapper.eq(Notification::getIsPushed, true);
        }
        wrapper.orderByDesc(Notification::getCreatedAt);

        Page<Notification> p = new Page<>(page, size);
        Page<Notification> result = notificationMapper.selectPage(p, wrapper);
        return result.getRecords().stream()
                .map(this::convertToResponse)
                .toList();
    }

    @Override
    public void markAsRead(String notificationId, String userId) {
        log.info("markAsRead notificationId={} userId={}", notificationId, userId);
        LambdaUpdateWrapper<Notification> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Notification::getId, notificationId)
               .eq(Notification::getUserId, userId)
               .set(Notification::getIsRead, true);
        int updated = notificationMapper.update(null, wrapper);
        log.info("markAsRead done notificationId={} rows={}", notificationId, updated);

        // 通过 SSE 通知前端更新未读数量
        sseBroadcaster.broadcastUnreadCount(userId, getUnreadCount(userId));
    }

    @Override
    public void markAllAsRead(String userId) {
        log.info("markAllAsRead userId={}", userId);
        LambdaUpdateWrapper<Notification> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Notification::getUserId, userId)
               .eq(Notification::getIsRead, false)
               .set(Notification::getIsRead, true);
        int updated = notificationMapper.update(null, wrapper);
        log.info("markAllAsRead done userId={} rows={}", userId, updated);

        // 通过 SSE 通知前端更新未读数量
        sseBroadcaster.broadcastUnreadCount(userId, 0);
    }

    @Override
    public int getUnreadCount(String userId) {
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Notification::getUserId, userId)
               .eq(Notification::getIsRead, false);
        int count = Math.toIntExact(notificationMapper.selectCount(wrapper));
        log.debug("getUnreadCount userId={} count={}", userId, count);
        return count;
    }

    @Override
    public void deleteNotification(String notificationId, String userId) {
        log.info("deleteNotification notificationId={} userId={}", notificationId, userId);
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Notification::getId, notificationId)
               .eq(Notification::getUserId, userId);
        int deleted = notificationMapper.delete(wrapper);
        log.info("deleteNotification done notificationId={} rows={}", notificationId, deleted);

        // 通过 SSE 通知前端更新未读数量
        sseBroadcaster.broadcastUnreadCount(userId, getUnreadCount(userId));
    }

    private NotificationResponse convertToResponse(Notification notification) {
        return NotificationResponse.builder()
                .id(notification.getId())
                .type(notification.getType())
                .title(notification.getTitle())
                .message(notification.getMessage())
                .isRead(notification.getIsRead() != null ? notification.getIsRead() : false)
                .isPushed(notification.getIsPushed() != null ? notification.getIsPushed() : false)
                .refId(notification.getRefId())
                .refType(notification.getRefType())
                .createdAt(notification.getCreatedAt() != null 
                        ? notification.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toString() 
                        : null)
                .build();
    }
}
