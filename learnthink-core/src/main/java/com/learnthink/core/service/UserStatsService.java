package com.learnthink.core.service;

import com.learnthink.common.dto.user.DailyActivityResponse;
import com.learnthink.common.dto.user.DailyDetailResponse;
import com.learnthink.common.dto.user.LearningStatsResponse;
import java.time.LocalDate;

/**
 * 学习统计服务接口
 */
public interface UserStatsService {
    /** 获取用户在指定课程下的学习统计 */
    LearningStatsResponse getStats(String userId, String courseId);

    /** 记录心跳，按日累加学习时长 */
    void recordHeartbeat(String userId, String courseId, int deltaSeconds);

    /** 获取日历热力图数据 */
    DailyActivityResponse getDailyActivity(String userId, String courseId, LocalDate startDate, LocalDate endDate);

    /** 获取某日学习详情 */
    DailyDetailResponse getDailyDetail(String userId, String courseId, LocalDate date);
}
