package com.learnthink.core.service;

import com.learnthink.common.dto.profile.ProfileChatRequest;
import com.learnthink.common.dto.profile.ProfileChatResponse;
import com.learnthink.common.dto.profile.ProfileVersionItemDto;

import java.util.List;
import java.util.Map;

public interface ProfileService {

    ProfileChatResponse processChat(String userId, ProfileChatRequest request);

    Map<String, Object> getProfile(String userId, String courseId);

    List<ProfileVersionItemDto> getProfileVersions(String userId, String courseId, int limit);

    /**
     * 增量更新画像：分析最近对话提取变化，merge 后保存为新版本。
     * 无覆盖度阈值，有变化就保存。每轮对话后调用。
     *
     * @param userId   用户ID
     * @param courseId 课程ID
     * @param messages 本轮完整消息列表（原始格式）
     * @param chatId   会话ID（用于关联 ProfileVersion.source_chat_ids）
     * @return 如果有新版本返回完整画像，否则返回空Map
     */
    Map<String, Object> updateProfileDelta(String userId, String courseId, List<Map<String, String>> messages, String chatId);
}
