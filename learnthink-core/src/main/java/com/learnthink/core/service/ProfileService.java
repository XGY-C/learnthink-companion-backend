package com.learnthink.core.service;

import com.learnthink.common.dto.profile.ProfileMdSet;
import com.learnthink.common.dto.profile.ProfileVersionItemDto;

import java.util.List;
import java.util.Map;

/**
 * 用户画像服务接口
 * <p>负责画像版本管理、查询以及会话结束时的两步 LLM 流水线更新。</p>
 */
public interface ProfileService {

    /**
     * 获取用户完整画像数据（兼容格式，含 display_json 和旧维度数据）
     */
    Map<String, Object> getProfile(String userId, String courseId);

    /**
     * 获取画像版本历史
     */
    List<ProfileVersionItemDto> getProfileVersions(String userId, String courseId, int limit);

    /**
     * 会话结束总入口（Step 0 → Step 1 → Step 1.5 → Step 2 → Step 3）
     *
     * @param userId   用户 ID
     * @param courseId 课程 ID
     * @param chatId   会话 ID
     * @param messages 会话完整消息列表
     */
    void handleChatEnd(String userId, String courseId, String chatId, List<Map<String, String>> messages);

    /**
     * 获取当前最新版本的三份 MD + display_json
     */
    ProfileMdSet getCurrentMd(String userId, String courseId);

    /**
     * 运行时解析 Markdown 中 [key] 行
     *
     * @param mdText Markdown 文本
     * @return key→value 映射
     */
    Map<String, String> parseProfileMd(String mdText);
}
