package com.learnthink.core.service.admin.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.common.dto.admin.AdminStudentResponse;
import com.learnthink.core.domain.entity.LearningEvent;
import com.learnthink.core.domain.entity.User;
import com.learnthink.core.domain.entity.UserCourseEnrollment;
import com.learnthink.core.domain.entity.UserStats;
import com.learnthink.core.repository.LearningEventMapper;
import com.learnthink.core.repository.UserCourseEnrollmentMapper;
import com.learnthink.core.repository.UserMapper;
import com.learnthink.core.repository.UserStatsMapper;
import com.learnthink.core.service.admin.AdminStudentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminStudentServiceImpl implements AdminStudentService {

    private final UserMapper userMapper;
    private final UserCourseEnrollmentMapper enrollmentMapper;
    private final UserStatsMapper userStatsMapper;
    private final LearningEventMapper learningEventMapper;

    @Override
    public List<AdminStudentResponse> listStudents(String search, String grade, String major, String status, String courseId) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getRole, "student");

        if (StringUtils.hasText(search)) {
            wrapper.and(w -> w
                .like(User::getUsername, search)
                .or().like(User::getEmail, search)
                .or().like(User::getDisplayName, search));
        }
        if (StringUtils.hasText(grade)) {
            wrapper.eq(User::getGrade, grade);
        }
        if (StringUtils.hasText(major)) {
            wrapper.eq(User::getMajor, major);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(User::getStatus, status);
        }
        wrapper.orderByDesc(User::getCreatedAt);

        List<User> users = userMapper.selectList(wrapper);
        if (users.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> userIds = users.stream().map(User::getId).collect(Collectors.toList());

        // 批量获取课程数量
        Map<String, Integer> courseCountMap = new HashMap<>();
        List<UserCourseEnrollment> enrollments = enrollmentMapper.selectList(
            new LambdaQueryWrapper<UserCourseEnrollment>().in(UserCourseEnrollment::getUserId, userIds));
        for (UserCourseEnrollment e : enrollments) {
            courseCountMap.merge(e.getUserId(), 1, Integer::sum);
        }

        // 批量获取总学习时长（跨所有课程的 SUM）
        Map<String, Integer> learningMinutesMap = new HashMap<>();
        List<UserStats> allStats = userStatsMapper.selectList(
            new LambdaQueryWrapper<UserStats>().in(UserStats::getUserId, userIds));
        for (UserStats s : allStats) {
            int minutes = s.getTotalLearningMinutes() != null ? s.getTotalLearningMinutes() : 0;
            learningMinutesMap.merge(s.getUserId(), minutes, Integer::sum);
        }

        // 批量获取最后活动时间（单次查询，内存分组）
        Map<String, LocalDateTime> lastActiveMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            List<LearningEvent> recentEvents = learningEventMapper.selectList(
                new LambdaQueryWrapper<LearningEvent>()
                    .in(LearningEvent::getUserId, userIds)
                    .orderByDesc(LearningEvent::getCreatedAt));
            for (LearningEvent e : recentEvents) {
                lastActiveMap.putIfAbsent(e.getUserId(), e.getCreatedAt());
            }
        }

        return users.stream().map(u -> AdminStudentResponse.builder()
            .id(u.getId())
            .username(u.getUsername())
            .email(u.getEmail())
            .displayName(u.getDisplayName())
            .avatarUrl(u.getAvatarUrl())
            .major(u.getMajor())
            .grade(u.getGrade())
            .role(u.getRole())
            .status(u.getStatus() != null ? u.getStatus() : "enabled")
            .courseCount(courseCountMap.getOrDefault(u.getId(), 0))
            .totalLearningMinutes(learningMinutesMap.getOrDefault(u.getId(), 0))
            .lastActiveAt(lastActiveMap.containsKey(u.getId())
                ? lastActiveMap.get(u.getId()).toString() : null)
            .createdAt(u.getCreatedAt() != null ? u.getCreatedAt().toString() : null)
            .build()).collect(Collectors.toList());
    }

    @Override
    public void updateStudentStatus(String studentId, String status) {
        User user = userMapper.selectById(studentId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        if (!"student".equals(user.getRole())) {
            throw new RuntimeException("只能管理学生账号");
        }
        if (!"enabled".equals(status) && !"disabled".equals(status)) {
            throw new RuntimeException("无效的状态值，仅支持 enabled / disabled");
        }
        user.setStatus(status);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
    }
}
