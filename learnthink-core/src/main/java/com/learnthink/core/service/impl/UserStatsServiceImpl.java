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

        // 2. 雷达数据：取最新画像版本的 display_json
        List<LearningStatsResponse.RadarEntry> radar = new ArrayList<>();
        LambdaQueryWrapper<ProfileVersion> pvWrapper = new LambdaQueryWrapper<>();
        pvWrapper.eq(ProfileVersion::getUserId, userId)
                 .eq(ProfileVersion::getCourseId, courseId)
                 .orderByDesc(ProfileVersion::getVersion)
                 .last("LIMIT 1");
        ProfileVersion latestPv = profileVersionMapper.selectOne(pvWrapper);
        if (latestPv != null && latestPv.getDisplayJson() != null) {
            try {
                JsonNode display = objectMapper.readTree(latestPv.getDisplayJson());
                JsonNode dims = display.get("dimensions");
                if (dims != null && dims.isArray()) {
                    for (JsonNode dimName : dims) {
                        radar.add(LearningStatsResponse.RadarEntry.builder()
                                .name(dimName.asText())
                                .value(70)
                                .build());
                    }
                }
            } catch (Exception ignored) {
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
            if (pv.getDisplayJson() != null) {
                try {
                    JsonNode display = objectMapper.readTree(pv.getDisplayJson());
                    JsonNode coreSummary = display.at("/core/summary");
                    if (!coreSummary.isMissingNode()) {
                        summaryItems.add(coreSummary.asText());
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

}
