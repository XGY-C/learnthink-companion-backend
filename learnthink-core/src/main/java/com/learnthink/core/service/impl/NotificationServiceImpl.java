package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.learnthink.common.dto.user.NotificationResponse;
import com.learnthink.core.domain.entity.Notification;
import com.learnthink.core.repository.NotificationMapper;
import com.learnthink.core.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationMapper notificationMapper;

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
        LambdaUpdateWrapper<Notification> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Notification::getId, notificationId)
               .eq(Notification::getUserId, userId)
               .set(Notification::getIsRead, true);
        notificationMapper.update(null, wrapper);
    }

    @Override
    public void markAllAsRead(String userId) {
        LambdaUpdateWrapper<Notification> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Notification::getUserId, userId)
               .eq(Notification::getIsRead, false)
               .set(Notification::getIsRead, true);
        notificationMapper.update(null, wrapper);
    }

    @Override
    public int getUnreadCount(String userId) {
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Notification::getUserId, userId)
               .eq(Notification::getIsRead, false);
        return Math.toIntExact(notificationMapper.selectCount(wrapper));
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
                        ? notification.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) 
                        : null)
                .build();
    }
}
