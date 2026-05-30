package com.learnthink.core.service;

import com.learnthink.common.dto.profile.ProfileChatRequest;
import com.learnthink.common.dto.profile.ProfileChatResponse;
import com.learnthink.common.dto.profile.ProfileVersionItemDto;

import java.util.List;
import java.util.Map;

/**
 * 用户画像服务接口
 * <p>负责画像构建对话处理、画像数据查询及版本管理。
 * 画像包含7个维度（学习风格、知识水平、兴趣偏好等），
 * 通过对话逐步收集信息后自动分析生成。</p>
 */
public interface ProfileService {

    /**
     * 处理画像构建对话
     * <p>将用户消息提供给LLM驱动的话术引擎，
     * 获取回复并通过增量更新机制更新画像数据。</p>
     *
     * @param userId  用户ID
     * @param request 对话请求（包含课程ID、用户消息等）
     * @return 对话响应（包含LLM回复和画像更新状态）
     */
    ProfileChatResponse processChat(String userId, ProfileChatRequest request);

    /**
     * 获取用户完整画像数据
     *
     * @param userId   用户ID
     * @param courseId 课程ID
     * @return 画像数据Map（包含各维度评分、摘要、推荐策略等）
     */
    Map<String, Object> getProfile(String userId, String courseId);

    /**
     * 获取画像版本历史
     *
     * @param userId   用户ID
     * @param courseId 课程ID
     * @param limit    返回条目数上限
     * @return 画像版本项列表（按版本号降序排列）
     */
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
