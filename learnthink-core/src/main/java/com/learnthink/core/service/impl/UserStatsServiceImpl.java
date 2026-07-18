package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.user.DailyActivityResponse;
import com.learnthink.common.dto.user.DailyDetailResponse;
import com.learnthink.common.dto.user.LearningStatsResponse;
import com.learnthink.core.domain.entity.ChatMessage;
import com.learnthink.core.domain.entity.ChatSession;
import com.learnthink.core.domain.entity.DailyLearningLog;
import com.learnthink.core.domain.entity.LearningEvent;
import com.learnthink.core.domain.entity.ProfileVersion;
import com.learnthink.core.domain.entity.QuizAttempt;
import com.learnthink.core.domain.entity.UserStats;
import com.learnthink.core.repository.ChatMessageMapper;
import com.learnthink.core.repository.ChatSessionMapper;
import com.learnthink.core.repository.DailyLearningLogMapper;
import com.learnthink.core.repository.LearningEventMapper;
import com.learnthink.core.repository.LearningRecordMapper;
import com.learnthink.core.repository.ProfileVersionMapper;
import com.learnthink.core.repository.QuizAttemptMapper;
import com.learnthink.core.repository.ResourcePackMapper;
import com.learnthink.core.repository.UserStatsMapper;
import com.learnthink.core.service.UserStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserStatsServiceImpl implements UserStatsService {

    private final UserStatsMapper userStatsMapper;
    private final ProfileVersionMapper profileVersionMapper;
    private final LearningRecordMapper learningRecordMapper;
    private final DailyLearningLogMapper dailyLearningLogMapper;
    private final QuizAttemptMapper quizAttemptMapper;
    private final LearningEventMapper learningEventMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ResourcePackMapper resourcePackMapper;
    private final ObjectMapper objectMapper;

    @Override
    public LearningStatsResponse getStats(String userId, String courseId) {
        // 1. 查询汇总统计
        LambdaQueryWrapper<UserStats> statsWrapper = new LambdaQueryWrapper<>();
        statsWrapper.eq(UserStats::getUserId, userId)
                     .eq(UserStats::getCourseId, courseId);
        UserStats stats = userStatsMapper.selectOne(statsWrapper);

        int todaySeconds = learningRecordMapper.sumTodaySeconds(userId, courseId);
        int todayMinutes = todaySeconds / 60;
        int totalMinutes = stats != null && stats.getTotalLearningMinutes() != null ? stats.getTotalLearningMinutes() : 0;
        LocalDate today = LocalDate.now();
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1);
        int weekSeconds = dailyLearningLogMapper.sumSecondsByDateRange(userId, courseId, monday, today);
        int weekMinutes = weekSeconds / 60;

        int packs = resourcePackMapper.countByUserAndCourse(userId, courseId);
        int weekPacks = resourcePackMapper.countByUserAndCourseSince(userId, courseId, monday.atStartOfDay());
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
                    .createdAt(pv.getCreatedAt() != null ? pv.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null)
                    .trigger("chat")
                    .summary(summaryItems)
                    .build());
        }

        return LearningStatsResponse.builder()
                .todayMinutes(todayMinutes)
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
                .recentActivities(buildRecentActivities(userId, courseId))
                .build();
    }

    /**
     * 聚合最近学习动态：对话 / 练习 / 阅读，按时间倒序取前 6 条
     */
    private List<LearningStatsResponse.RecentActivityEntry> buildRecentActivities(String userId, String courseId) {
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        List<LearningStatsResponse.RecentActivityEntry> all = new ArrayList<>();

        // 1. 对话
        LambdaQueryWrapper<ChatSession> chatWrapper = new LambdaQueryWrapper<>();
        chatWrapper.eq(ChatSession::getUserId, userId)
                   .eq(ChatSession::getCourseId, courseId)
                   .ge(ChatSession::getCreatedAt, since)
                   .orderByDesc(ChatSession::getCreatedAt)
                   .last("LIMIT 10");
        for (ChatSession s : chatSessionMapper.selectList(chatWrapper)) {
            String chatType = inferChatType(s);
            String prefix = switch (chatType) {
                case "lecture" -> "辅导「";
                case "plan" -> "规划「";
                case "resource" -> "资源「";
                default -> "对话「";
            };
            all.add(LearningStatsResponse.RecentActivityEntry.builder()
                    .type(chatType)
                    .label(prefix + (s.getTitle() != null && !s.getTitle().isEmpty() ? s.getTitle() : "未命名对话") + "」")
                    .detail(s.getMessageCount() != null && s.getMessageCount() > 0 ? s.getMessageCount() + " 条消息" : null)
                    .time(toIso(s.getCreatedAt()))
                    .build());
        }

        // 2. 练习
        LambdaQueryWrapper<QuizAttempt> quizWrapper = new LambdaQueryWrapper<>();
        quizWrapper.eq(QuizAttempt::getUserId, userId)
                   .eq(QuizAttempt::getCourseId, courseId)
                   .ge(QuizAttempt::getCreatedAt, since)
                   .orderByDesc(QuizAttempt::getCreatedAt)
                   .last("LIMIT 10");
        for (QuizAttempt q : quizAttemptMapper.selectList(quizWrapper)) {
            StringBuilder detail = new StringBuilder();
            if (q.getScore() != null) {
                detail.append("得分 ").append(q.getScore().intValue());
            }
            if (q.getDurationSeconds() != null && q.getDurationSeconds() > 0) {
                if (detail.length() > 0) detail.append(" · ");
                detail.append("用时 ").append(q.getDurationSeconds() / 60).append(" 分钟");
            }
            all.add(LearningStatsResponse.RecentActivityEntry.builder()
                    .type("practice")
                    .label("练习「" + (q.getTopic() != null && !q.getTopic().isEmpty() ? q.getTopic() : "未命名练习") + "」")
                    .detail(detail.length() > 0 ? detail.toString() : null)
                    .time(toIso(q.getCreatedAt()))
                    .build());
        }

        // 3. 学习/阅读（resource_opened 事件）
        LambdaQueryWrapper<LearningEvent> eventWrapper = new LambdaQueryWrapper<>();
        eventWrapper.eq(LearningEvent::getUserId, userId)
                    .eq(LearningEvent::getCourseId, courseId)
                    .eq(LearningEvent::getEventType, "resource_opened")
                    .ge(LearningEvent::getCreatedAt, since)
                    .orderByDesc(LearningEvent::getCreatedAt)
                    .last("LIMIT 10");
        for (LearningEvent e : learningEventMapper.selectList(eventWrapper)) {
            String title = "未知资源";
            if (e.getPayloadJson() != null) {
                try {
                    JsonNode payload = objectMapper.readTree(e.getPayloadJson());
                    if (payload.has("title")) title = payload.get("title").asText();
                } catch (Exception ignored) {}
            }
            all.add(LearningStatsResponse.RecentActivityEntry.builder()
                    .type("reading")
                    .label("学习「" + title + "」")
                    .detail(null)
                    .time(toIso(e.getCreatedAt()))
                    .build());
        }

        // 4. 学习路径节点完成（node_completed 事件）
        LambdaQueryWrapper<LearningEvent> nodeWrapper = new LambdaQueryWrapper<>();
        nodeWrapper.eq(LearningEvent::getUserId, userId)
                   .eq(LearningEvent::getCourseId, courseId)
                   .eq(LearningEvent::getEventType, "node_completed")
                   .ge(LearningEvent::getCreatedAt, since)
                   .orderByDesc(LearningEvent::getCreatedAt)
                   .last("LIMIT 10");
        for (LearningEvent e : learningEventMapper.selectList(nodeWrapper)) {
            String title = "未知节点";
            if (e.getPayloadJson() != null) {
                try {
                    JsonNode payload = objectMapper.readTree(e.getPayloadJson());
                    if (payload.has("title")) title = payload.get("title").asText();
                } catch (Exception ignored) {}
            }
            all.add(LearningStatsResponse.RecentActivityEntry.builder()
                    .type("path")
                    .label("完成节点「" + title + "」")
                    .detail(null)
                    .time(toIso(e.getCreatedAt()))
                    .build());
        }

        // 按时间倒序，取前 6 条
        all.sort((a, b) -> {
            String ta = a.getTime() != null ? a.getTime() : "";
            String tb = b.getTime() != null ? b.getTime() : "";
            return tb.compareTo(ta);
        });
        return all.size() > 6 ? all.subList(0, 6) : all;
    }

    /**
     * 基于会话消息的 mode / metadataJson 推断会话类型（优先级：lecture > plan > resource）。
     * 与 ChatSessionService.getSessions 中的推断逻辑保持一致。
     */
    private String inferChatType(ChatSession session) {
        List<ChatMessage> messages = chatMessageMapper.selectBySessionId(session.getId());
        if (messages == null || messages.isEmpty()) {
            return "chat";
        }
        boolean hasLecture = messages.stream().anyMatch(m ->
            "lecture".equals(m.getMode()) || "smart".equals(m.getMode()) ||
            (m.getMetadataJson() != null && m.getMetadataJson().contains("tutoring_session_id")));
        boolean hasPlan = messages.stream().anyMatch(m ->
            "plan".equals(m.getMode()) ||
            (m.getMetadataJson() != null && m.getMetadataJson().contains("plan_id")));
        boolean hasResource = messages.stream().anyMatch(m ->
            "resource".equals(m.getMode()) ||
            (m.getMetadataJson() != null && m.getMetadataJson().contains("task_id")));
        if (hasLecture) return "lecture";
        if (hasPlan) return "plan";
        if (hasResource) return "resource";
        return "chat";
    }

    /** LocalDateTime -> ISO-8601 字符串 */
    private String toIso(LocalDateTime ldt) {
        return ldt != null ? ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null;
    }

    // ==================== 学习日历相关 ====================

    @Override
    public void recordHeartbeat(String userId, String courseId, int deltaSeconds) {
        LocalDate today = LocalDate.now();
        // 1. upsert daily_learning_logs（按日累加）
        dailyLearningLogMapper.upsertDelta(userId, courseId, today, deltaSeconds);
        // 2. 更新 user_stats 汇总（同步 total_learning_minutes）
        userStatsMapper.addLearningMinutes(userId, courseId, deltaSeconds / 60);
    }

    @Override
    public DailyActivityResponse getDailyActivity(String userId, String courseId, LocalDate startDate, LocalDate endDate) {
        // 查询数据
        Map<LocalDate, Integer> dateMap;
        if (courseId != null && !courseId.isEmpty()) {
            List<DailyLearningLog> logs = dailyLearningLogMapper.findByDateRange(userId, courseId, startDate, endDate);
            dateMap = logs.stream().collect(Collectors.toMap(
                    DailyLearningLog::getLogDate,
                    DailyLearningLog::getLearningSeconds,
                    Integer::sum));
        } else {
            List<Map<String, Object>> rows = dailyLearningLogMapper.findAllCourseByDateRange(userId, startDate, endDate);
            dateMap = new java.util.HashMap<>();
            for (Map<String, Object> row : rows) {
                java.sql.Date sqlDate = (java.sql.Date) row.get("log_date");
                int seconds = ((Number) row.get("learning_seconds")).intValue();
                dateMap.merge(sqlDate.toLocalDate(), seconds, Integer::sum);
            }
        }

        // 填充日期范围内每一天
        List<DailyActivityResponse.DayData> days = new ArrayList<>();
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            int seconds = dateMap.getOrDefault(cursor, 0);
            DailyActivityResponse.DayData d = new DailyActivityResponse.DayData();
            d.setDate(cursor.toString());
            d.setLearningSeconds(seconds);
            d.setLevel(secondsToLevel(seconds));
            days.add(d);
            cursor = cursor.plusDays(1);
        }

        // 计算统计
        DailyActivityResponse.Stats stats = computeStats(dateMap, endDate);

        DailyActivityResponse resp = new DailyActivityResponse();
        resp.setDays(days);
        resp.setSummary(stats);
        return resp;
    }

    @Override
    public DailyDetailResponse getDailyDetail(String userId, String courseId, LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");

        DailyDetailResponse resp = new DailyDetailResponse();
        resp.setDate(date.toString());

        // 1. 当日总学习时长
        int totalSeconds;
        if (courseId != null && !courseId.isEmpty()) {
            List<DailyLearningLog> logs = dailyLearningLogMapper.findByDateRange(userId, courseId, date, date);
            totalSeconds = logs.stream().mapToInt(DailyLearningLog::getLearningSeconds).sum();
        } else {
            List<Map<String, Object>> rows = dailyLearningLogMapper.findAllCourseByDateRange(userId, date, date);
            totalSeconds = rows.stream().mapToInt(r -> ((Number) r.get("learning_seconds")).intValue()).sum();
        }
        resp.setTotalLearningSeconds(totalSeconds);

        // 2. 当日练习记录
        List<QuizAttempt> attempts = quizAttemptMapper.findByUserAndDateRange(userId, courseId, start, end);
        resp.setQuizzes(attempts.stream().map(a -> {
            DailyDetailResponse.QuizItem item = new DailyDetailResponse.QuizItem();
            item.setTime(a.getCreatedAt() != null ? a.getCreatedAt().format(timeFmt) : "");
            item.setTopic(a.getTopic());
            item.setScore(a.getScore());
            item.setDurationSeconds(a.getDurationSeconds());
            return item;
        }).collect(Collectors.toList()));

        // 3. 当日资源学习记录（从 learning_events 中筛选 resource_opened）
        List<LearningEvent> events = learningEventMapper.findByUserAndDateRange(userId, courseId, start, end);
        resp.setResources(events.stream()
                .filter(e -> "resource_opened".equals(e.getEventType()))
                .map(e -> {
                    DailyDetailResponse.ResourceItem item = new DailyDetailResponse.ResourceItem();
                    item.setTime(e.getCreatedAt() != null ? e.getCreatedAt().format(timeFmt) : "");
                    // 从 payload_json 中提取 title 和 type
                    String title = "未知资源";
                    String type = "doc";
                    if (e.getPayloadJson() != null) {
                        try {
                            JsonNode payload = objectMapper.readTree(e.getPayloadJson());
                            if (payload.has("title")) title = payload.get("title").asText();
                            if (payload.has("resource_type")) type = payload.get("resource_type").asText();
                        } catch (Exception ignored) {}
                    }
                    item.setTitle(title);
                    item.setType(type);
                    item.setStatus("opened");
                    return item;
                }).collect(Collectors.toList()));

        // 4. 当日对话记录
        List<ChatSession> sessions = chatSessionMapper.findByUserAndDateRange(userId, courseId, start, end);
        resp.setChats(sessions.stream().map(s -> {
            DailyDetailResponse.ChatItem item = new DailyDetailResponse.ChatItem();
            item.setTime(s.getCreatedAt() != null ? s.getCreatedAt().format(timeFmt) : "");
            item.setTitle(s.getTitle());
            item.setMessageCount(s.getMessageCount());
            return item;
        }).collect(Collectors.toList()));

        return resp;
    }

    /** 秒数 -> 热力等级 0-4 */
    private int secondsToLevel(int seconds) {
        if (seconds == 0) return 0;
        if (seconds < 3600) return 1;       // < 1h
        if (seconds < 7200) return 2;       // 1-2h
        if (seconds < 10800) return 3;      // 2-3h
        return 4;                            // > 3h
    }

    private DailyActivityResponse.Stats computeStats(Map<LocalDate, Integer> dateMap, LocalDate endDate) {
        DailyActivityResponse.Stats s = new DailyActivityResponse.Stats();
        // 只统计有学习时长的日期
        Map<LocalDate, Integer> activeMap = dateMap.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        s.setTotalActiveDays(activeMap.size());

        // 当前连续天数（从 endDate 往前数）
        int streak = 0;
        LocalDate cursor = endDate;
        while (activeMap.containsKey(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        s.setCurrentStreak(streak);

        // 本月活跃天数
        int monthActive = (int) activeMap.keySet().stream()
                .filter(d -> d.getYear() == endDate.getYear() && d.getMonth() == endDate.getMonth())
                .count();
        s.setMonthActiveDays(monthActive);

        // 最长连续天数
        List<LocalDate> sortedDates = activeMap.keySet().stream().sorted().collect(Collectors.toList());
        int maxStreak = 0, tempStreak = 0;
        LocalDate prev = null;
        for (LocalDate d : sortedDates) {
            if (prev != null && d.equals(prev.plusDays(1))) {
                tempStreak++;
            } else {
                tempStreak = 1;
            }
            maxStreak = Math.max(maxStreak, tempStreak);
            prev = d;
        }
        s.setMaxStreak(maxStreak);

        return s;
    }

}
