package com.learnthink.web.controller.teacher;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.domain.entity.*;
import com.learnthink.core.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/teacher/students")
@RequiredArgsConstructor
public class TeacherStudentController {

    private final CourseMapper courseMapper;
    private final UserCourseEnrollmentMapper enrollmentMapper;
    private final UserMapper userMapper;
    private final UserStatsMapper userStatsMapper;
    private final LearningEventMapper learningEventMapper;
    private final ProfileVersionMapper profileVersionMapper;

    @GetMapping
    public Result<List<Map<String, Object>>> listStudents() {
        String userId = UserContextUtil.getCurrentUserId();

        List<Course> myCourses = courseMapper.selectList(
                new LambdaQueryWrapper<Course>()
                        .eq(Course::getTeacherId, userId)
                        .isNull(Course::getDeletedAt)
        );
        if (myCourses.isEmpty()) return Result.success(List.of());

        List<String> courseIds = myCourses.stream().map(Course::getId).toList();

        List<UserCourseEnrollment> enrollments = enrollmentMapper.selectList(
                new LambdaQueryWrapper<UserCourseEnrollment>()
                        .in(UserCourseEnrollment::getCourseId, courseIds)
        );
        if (enrollments.isEmpty()) return Result.success(List.of());

        Set<String> studentIds = enrollments.stream()
                .map(UserCourseEnrollment::getUserId)
                .collect(Collectors.toSet());

        List<User> students = userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .in(User::getId, studentIds)
                        .eq(User::getRole, "student")
        );

        List<String> studentIdList = students.stream().map(User::getId).toList();

        Map<String, Integer> learningMinutesMap = new HashMap<>();
        List<UserStats> allStats = userStatsMapper.selectList(
                new LambdaQueryWrapper<UserStats>().in(UserStats::getUserId, studentIdList));
        for (UserStats s : allStats) {
            int minutes = s.getTotalLearningMinutes() != null ? s.getTotalLearningMinutes() : 0;
            learningMinutesMap.merge(s.getUserId(), minutes, Integer::sum);
        }

        Map<String, String> lastActiveMap = new HashMap<>();
        if (!studentIdList.isEmpty()) {
            List<LearningEvent> recentEvents = learningEventMapper.selectList(
                    new LambdaQueryWrapper<LearningEvent>()
                            .in(LearningEvent::getUserId, studentIdList)
                            .orderByDesc(LearningEvent::getCreatedAt));
            for (LearningEvent e : recentEvents) {
                lastActiveMap.putIfAbsent(e.getUserId(),
                        e.getCreatedAt() != null ? e.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null);
            }
        }

        Map<String, List<String>> studentCourses = new HashMap<>();
        for (UserCourseEnrollment e : enrollments) {
            studentCourses.computeIfAbsent(e.getUserId(), k -> new ArrayList<>()).add(e.getCourseId());
        }

        Map<String, String> courseNameMap = myCourses.stream()
                .collect(Collectors.toMap(Course::getId, Course::getName));

        List<Map<String, Object>> result = students.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("username", s.getUsername());
            m.put("email", s.getEmail());
            m.put("displayName", s.getDisplayName());
            m.put("avatarUrl", s.getAvatarUrl());
            m.put("major", s.getMajor());
            m.put("grade", s.getGrade());
            m.put("status", s.getStatus() != null ? s.getStatus() : "enabled");
            m.put("totalLearningMinutes", learningMinutesMap.getOrDefault(s.getId(), 0));
            m.put("lastActiveAt", lastActiveMap.get(s.getId()));
            List<String> enrolledCourseNames = studentCourses.getOrDefault(s.getId(), List.of())
                    .stream().map(cid -> courseNameMap.getOrDefault(cid, cid)).toList();
            m.put("courseNames", enrolledCourseNames);
            m.put("courseCount", enrolledCourseNames.size());
            return m;
        }).collect(Collectors.toList());

        return Result.success(result);
    }

    @GetMapping("/{studentId}/stats")
    public Result<Map<String, Object>> getStudentStats(@PathVariable String studentId) {
        List<UserStats> statsList = userStatsMapper.selectList(
                new LambdaQueryWrapper<UserStats>().eq(UserStats::getUserId, studentId));
        Map<String, Object> result = new LinkedHashMap<>();
        int totalMinutes = 0, totalPacks = 0, totalQuiz = 0, weekMinutes = 0, weekPacks = 0, weekQuiz = 0;
        double avgScore = 0;
        int masteredNodes = 0, totalNodes = 0, weakCount = 0;
        for (UserStats s : statsList) {
            totalMinutes += s.getTotalLearningMinutes() != null ? s.getTotalLearningMinutes() : 0;
            totalPacks += s.getTotalResourcePacks() != null ? s.getTotalResourcePacks() : 0;
            totalQuiz += s.getTotalQuizAttempts() != null ? s.getTotalQuizAttempts() : 0;
            avgScore += s.getTotalQuizScoreAvg() != null ? s.getTotalQuizScoreAvg().doubleValue() : 0;
            masteredNodes += s.getPathMasteredNodes() != null ? s.getPathMasteredNodes() : 0;
            totalNodes += s.getPathTotalNodes() != null ? s.getPathTotalNodes() : 0;
            weakCount += s.getCurrentWeakCount() != null ? s.getCurrentWeakCount() : 0;
            weekMinutes += s.getWeekLearningMinutes() != null ? s.getWeekLearningMinutes() : 0;
            weekPacks += s.getWeekResourcePacks() != null ? s.getWeekResourcePacks() : 0;
            weekQuiz += s.getWeekQuizAttempts() != null ? s.getWeekQuizAttempts() : 0;
        }
        if (!statsList.isEmpty()) avgScore /= statsList.size();
        result.put("totalLearningMinutes", totalMinutes);
        result.put("totalResourcePacks", totalPacks);
        result.put("totalQuizAttempts", totalQuiz);
        result.put("totalQuizScoreAvg", Math.round(avgScore * 100.0) / 100.0);
        result.put("pathMasteredNodes", masteredNodes);
        result.put("pathTotalNodes", totalNodes);
        result.put("currentWeakCount", weakCount);
        result.put("weekLearningMinutes", weekMinutes);
        result.put("weekResourcePacks", weekPacks);
        result.put("weekQuizAttempts", weekQuiz);
        return Result.success(result);
    }

    @GetMapping("/{studentId}/profiles")
    public Result<List<Map<String, Object>>> getStudentProfiles(@PathVariable String studentId) {
        List<ProfileVersion> versions = profileVersionMapper.selectList(
                new LambdaQueryWrapper<ProfileVersion>()
                        .eq(ProfileVersion::getUserId, studentId)
                        .orderByDesc(ProfileVersion::getVersion)
        );
        List<Map<String, Object>> result = versions.stream().map(v -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.getId());
            m.put("version", v.getVersion());
            m.put("coreProfileMd", v.getCoreProfileMd());
            m.put("learningProfileMd", v.getLearningProfileMd());
            m.put("knowledgeProfileMd", v.getKnowledgeProfileMd());
            m.put("displayJson", v.getDisplayJson());
            m.put("createdAt", v.getCreatedAt() != null ? v.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null);
            return m;
        }).collect(Collectors.toList());
        return Result.success(result);
    }
}
