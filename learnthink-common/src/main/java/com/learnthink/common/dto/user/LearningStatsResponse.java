package com.learnthink.common.dto.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.List;

/**
 * 学习统计响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearningStatsResponse {
    private int totalHours;
    private int weekHours;
    private int resourcePackCount;
    private int weekResourcePacks;
    private int pathProgressPercent;
    private int weakCount;
    private int prevWeakCount;
    private BigDecimal totalQuizScoreAvg;

    /** 雷达图数据：{ name, value } */
    private List<RadarEntry> radarData;

    /** 周活动：{ week, hours } */
    private List<WeeklyActivityEntry> weeklyActivity;

    /** 画像版本历史 */
    private List<ProfileHistoryEntry> profileHistory;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RadarEntry {
        private String name;
        private int value;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WeeklyActivityEntry {
        private String week;
        private int hours;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProfileHistoryEntry {
        private int version;
        private String createdAt;
        private String trigger;
        private List<String> summary;
    }
}
