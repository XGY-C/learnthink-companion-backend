package com.learnthink.core.service;

import com.learnthink.common.dto.user.NotificationResponse;
import java.util.List;

/**
 * 通知服务接口
 */
public interface NotificationService {
    /** 获取用户通知列表（按时间倒序），includeUnpushed=true 时同时返回未推送的通知 */
    List<NotificationResponse> getUserNotifications(String userId, boolean includeUnpushed, int page, int size);

    /** 标记单条已读 */
    void markAsRead(String notificationId, String userId);

    /** 全部已读 */
    void markAllAsRead(String userId);

    /** 未读数量 */
    int getUnreadCount(String userId);
}
