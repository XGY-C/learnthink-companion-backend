package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.user.LearningStatsResponse;
import com.learnthink.core.domain.entity.ProfileVersion;
import com.learnthink.core.domain.entity.UserStats;
import com.learnthink.core.repository.ProfileVersionMapper;
import com.learnthink.core.repository.UserStatsMapper;
import com.learnthink.core.service.UserStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserStatsServiceImpl implements UserStatsService {

    private final UserStatsMapper userStatsMapper;
    private final ProfileVersionMapper profileVersionMapper;
    private final ObjectMapper objectMapper;

    @Override
    public LearningStatsResponse getStats(String userId, String courseId) {
        // 1. 查询汇总统计
        LambdaQueryWrapper<UserStats> statsWrapper = new LambdaQueryWrapper<>();
        statsWrapper.eq(UserStats::getUserId, userId)
                     .eq(UserStats::getCourseId, courseId);
        UserStats stats = userStatsMapper.selectOne(statsWrapper);

        int totalMinutes = stats != null && stats.getTotalLearningMinutes() != null ? stats.getTotalLearningMinutes() : 0;
        int weekMinutes = stats != null && stats.getWeekLearningMinutes() != null ? stats.getWeekLearningMinutes() : 0;
        int packs = stats != null && stats.getTotalResourcePacks() != null ? stats.getTotalResourcePacks() : 0;
        int weekPacks = stats != null && stats.getWeekResourcePacks() != null ? stats.getWeekResourcePacks() : 0;
        int mastered = stats != null && stats.getPathMasteredNodes() != null ? stats.getPathMasteredNodes() : 0;
        int totalNodes = stats != null && stats.getPathTotalNodes() != null ? stats.getPathTotalNodes() : 0;
        int progress = totalNodes > 0 ? (int) Math.round((double) mastered / totalNodes * 100) : 0;
        int weak = stats != null && stats.getCurrentWeakCount() != null ? stats.getCurrentWeakCount() : 0;
        int prevWeak = stats != null && stats.getPrevWeakCount() != null ? stats.getPrevWeakCount() : 0;
        var quizAvg = stats != null ? stats.getTotalQuizScoreAvg() : null;

        // 2. 雷达数据：取最新画像版本的 dimensions
        List<LearningStatsResponse.RadarEntry> radar = new ArrayList<>();
        LambdaQueryWrapper<ProfileVersion> pvWrapper = new LambdaQueryWrapper<>();
        pvWrapper.eq(ProfileVersion::getUserId, userId)
                 .eq(ProfileVersion::getCourseId, courseId)
                 .orderByDesc(ProfileVersion::getVersion)
                 .last("LIMIT 1");
        ProfileVersion latestPv = profileVersionMapper.selectOne(pvWrapper);
        if (latestPv != null && latestPv.getDimensionsJson() != null) {
            try {
                JsonNode dimensions = objectMapper.readTree(latestPv.getDimensionsJson());
                for (JsonNode dim : dimensions) {
                    JsonNode valueNode = dim.get("value");
                    String label = dim.has("label") ? dim.get("label").asText() : dim.get("key").asText();
                    int val = extractDimensionScore(valueNode);
                    radar.add(LearningStatsResponse.RadarEntry.builder()
                            .name(label)
                            .value(val)
                            .build());
                }
            } catch (Exception ignored) {
                // dimensions 解析失败时返回空雷达
            }
        }

        // 3. 周活动数据
        List<LearningStatsResponse.WeeklyActivityEntry> weekly = new ArrayList<>();
        if (stats != null && stats.getWeeklyActivityJson() != null) {
            try {
                JsonNode arr = objectMapper.readTree(stats.getWeeklyActivityJson());
                for (JsonNode entry : arr) {
                    weekly.add(LearningStatsResponse.WeeklyActivityEntry.builder()
                            .week(entry.has("week") ? entry.get("week").asText() : "")
                            .hours(entry.has("hours") ? entry.get("hours").asInt() : 0)
                            .build());
                }
            } catch (Exception ignored) {
            }
        }

        // 4. 画像版本历史（最近10条）
        List<LearningStatsResponse.ProfileHistoryEntry> history = new ArrayList<>();
        LambdaQueryWrapper<ProfileVersion> histWrapper = new LambdaQueryWrapper<>();
        histWrapper.eq(ProfileVersion::getUserId, userId)
                   .eq(ProfileVersion::getCourseId, courseId)
                   .orderByDesc(ProfileVersion::getVersion)
                   .last("LIMIT 10");
        List<ProfileVersion> versions = profileVersionMapper.selectList(histWrapper);
        for (ProfileVersion pv : versions) {
            List<String> summaryItems = new ArrayList<>();
            if (pv.getSummaryJson() != null) {
                try {
                    JsonNode summaryArr = objectMapper.readTree(pv.getSummaryJson());
                    for (JsonNode s : summaryArr) {
                        summaryItems.add(s.asText());
                    }
                } catch (Exception ignored) {
                }
            }
            history.add(LearningStatsResponse.ProfileHistoryEntry.builder()
                    .version(pv.getVersion())
                    .createdAt(pv.getCreatedAt() != null ? pv.getCreatedAt().toString() : null)
                    .trigger("chat")
                    .summary(summaryItems)
                    .build());
        }

        return LearningStatsResponse.builder()
                .totalHours(totalMinutes / 60)
                .weekHours(weekMinutes / 60)
                .resourcePackCount(packs)
                .weekResourcePacks(weekPacks)
                .pathProgressPercent(progress)
                .weakCount(weak)
                .prevWeakCount(prevWeak)
                .totalQuizScoreAvg(quizAvg)
                .radarData(radar)
                .weeklyActivity(weekly)
                .profileHistory(history)
                .build();
    }

    /** 从画像维度 value 中提取数值分数（0-100） */
    private int extractDimensionScore(JsonNode valueNode) {
        if (valueNode == null) return 50;
        if (valueNode.isNumber()) return Math.min(100, Math.max(0, valueNode.asInt()));
        // value 可能是 JSON 对象，尝试提取 score / confidence / level 等字段
        if (valueNode.isObject()) {
            if (valueNode.has("score")) return Math.min(100, Math.max(0, valueNode.get("score").asInt()));
            if (valueNode.has("level")) {
                String level = valueNode.get("level").asText();
                return switch (level.toLowerCase()) {
                    case "expert", "advanced" -> 90;
                    case "intermediate" -> 65;
                    case "beginner" -> 35;
                    default -> 50;
                };
            }
            // 尝试计算 mastered/strong 标签占比
            int strong = valueNode.has("strong") ? valueNode.get("strong").size() : 0;
            int mastered = valueNode.has("mastered") ? valueNode.get("mastered").size() : 0;
            int weak = valueNode.has("weak") ? valueNode.get("weak").size() : 0;
            int total = strong + mastered + weak;
            if (total > 0) return (int) Math.round(((strong + mastered) * 85.0 + weak * 35.0) / total);
            return 50;
        }
        return 50;
    }
}
