package com.learnthink.common.dto.user;

import lombok.Data;
import java.util.List;

@Data
public class DailyActivityResponse {
    private List<DayData> days;
    private Stats summary;

    @Data
    public static class DayData {
        private String date;           // "2026-07-08"
        private Integer learningSeconds;
        private Integer level;         // 0-4 热力等级
    }

    @Data
    public static class Stats {
        private int currentStreak;     // 当前连续学习天数
        private int monthActiveDays;   // 本月活跃天数
        private int maxStreak;         // 最长连续天数
        private int totalActiveDays;   // 范围内总活跃天数
    }
}
