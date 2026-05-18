package com.learnthink.core.service;

import com.learnthink.common.dto.user.LearningStatsResponse;

/**
 * 学习统计服务接口
 */
public interface UserStatsService {
    /** 获取用户在指定课程下的学习统计 */
    LearningStatsResponse getStats(String userId, String courseId);
}
