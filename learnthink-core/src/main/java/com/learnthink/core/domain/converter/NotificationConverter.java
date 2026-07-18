package com.learnthink.core.domain.converter;

import com.learnthink.common.dto.user.NotificationResponse;
import com.learnthink.core.domain.entity.Notification;

/**
 * 通知DTO转换器
 */
public class NotificationConverter {
    
    /**
     * 将Notification实体转换为NotificationResponse DTO
     * @param notification 通知实体
     * @return 通知响应DTO
     */
    public static NotificationResponse toNotificationResponse(Notification notification) {
        if (notification == null) {
            return null;
        }
        
        return NotificationResponse.builder()
                .id(notification.getId())
                .type(notification.getType())
                .title(notification.getTitle())
                .message(notification.getMessage())
                .isRead(notification.getIsRead() != null && notification.getIsRead())
                .isPushed(notification.getIsPushed() != null && notification.getIsPushed())
                .refId(notification.getRefId())
                .refType(notification.getRefType())
                .createdAt(notification.getCreatedAt() != null ? notification.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null)
                .build();
    }
}
