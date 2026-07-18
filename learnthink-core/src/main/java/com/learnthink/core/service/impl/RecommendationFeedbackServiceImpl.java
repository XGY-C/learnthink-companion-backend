package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.core.domain.entity.RecommendationFeedback;
import com.learnthink.core.repository.RecommendationFeedbackMapper;
import com.learnthink.core.service.RecommendationFeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 推荐效果追踪服务实现
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RecommendationFeedbackServiceImpl implements RecommendationFeedbackService {

    private final RecommendationFeedbackMapper recommendationFeedbackMapper;

    @Override
    public void record(String userId, String courseId, String packId, String action, String source, String notificationId, String scoreJson) {
        RecommendationFeedback feedback = new RecommendationFeedback();
        feedback.setUserId(userId);
        feedback.setCourseId(courseId);
        feedback.setPackId(packId);
        feedback.setAction(action);
        feedback.setSource(source);
        feedback.setNotificationId(notificationId);
        feedback.setScoreJson(scoreJson);
        // id 由 ASSIGN_UUID 自动生成，createdAt 由数据库 DEFAULT CURRENT_TIMESTAMP 自动填充
        recommendationFeedbackMapper.insert(feedback);
        log.info("记录推荐反馈: userId={}, courseId={}, action={}", userId, courseId, action);
    }

    @Override
    public Map<String, Object> getStats(String userId, String courseId, int days) {
        LambdaQueryWrapper<RecommendationFeedback> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RecommendationFeedback::getUserId, userId)
               .eq(RecommendationFeedback::getCourseId, courseId)
               .ge(RecommendationFeedback::getCreatedAt, LocalDateTime.now().minusDays(days));

        List<RecommendationFeedback> feedbackList = recommendationFeedbackMapper.selectList(wrapper);

        // 按 action 分组统计数量
        Map<String, Long> countByAction = new HashMap<>();
        for (RecommendationFeedback feedback : feedbackList) {
            countByAction.merge(feedback.getAction(), 1L, Long::sum);
        }

        long totalShown = countByAction.getOrDefault("shown", 0L);
        long totalClicked = countByAction.getOrDefault("clicked", 0L);
        long totalStarted = countByAction.getOrDefault("started", 0L);
        long totalCompleted = countByAction.getOrDefault("completed", 0L);
        long totalDismissed = countByAction.getOrDefault("dismissed", 0L);

        // 点击率 = clicked / shown * 100，保留2位小数
        double ctr = totalShown > 0
                ? Math.round(totalClicked * 10000.0 / totalShown) / 100.0
                : 0.0;

        // 完成率 = completed / clicked * 100，保留2位小数
        double completionRate = totalClicked > 0
                ? Math.round(totalCompleted * 10000.0 / totalClicked) / 100.0
                : 0.0;

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalShown", totalShown);
        stats.put("totalClicked", totalClicked);
        stats.put("totalStarted", totalStarted);
        stats.put("totalCompleted", totalCompleted);
        stats.put("totalDismissed", totalDismissed);
        stats.put("ctr", ctr);
        stats.put("completionRate", completionRate);

        return stats;
    }
}
