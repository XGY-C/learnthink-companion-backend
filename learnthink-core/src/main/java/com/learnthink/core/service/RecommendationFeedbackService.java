package com.learnthink.core.service;

import java.util.Map;

/**
 * 推荐效果追踪服务接口
 */
public interface RecommendationFeedbackService {

    /**
     * 记录用户对推荐的反馈行为
     *
     * @param userId         用户ID
     * @param courseId       课程ID
     * @param packId         学习包ID
     * @param action         行为类型（shown/clicked/started/completed/dismissed）
     * @param source         推荐来源
     * @param notificationId 关联的通知ID
     * @param scoreJson      推荐评分信息（JSON）
     */
    void record(String userId, String courseId, String packId, String action, String source, String notificationId, String scoreJson);

    /**
     * 获取指定用户和课程在最近 N 天的推荐效果统计
     *
     * @param userId   用户ID
     * @param courseId 课程ID
     * @param days     统计天数
     * @return 统计结果
     */
    Map<String, Object> getStats(String userId, String courseId, int days);
}
