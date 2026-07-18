package com.learnthink.core.service.admin.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.common.dto.admin.AdminTeacherResponse;
import com.learnthink.core.domain.entity.Course;
import com.learnthink.core.domain.entity.User;
import com.learnthink.core.repository.CourseMapper;
import com.learnthink.core.repository.UserMapper;
import com.learnthink.core.service.UserService;
import com.learnthink.core.service.admin.AdminTeacherService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminTeacherServiceImpl implements AdminTeacherService {

    private final UserMapper userMapper;
    private final CourseMapper courseMapper;
    private final UserService userService;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public List<AdminTeacherResponse> listTeachers(String search, String status) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getRole, "teacher");

        if (StringUtils.hasText(search)) {
            wrapper.and(w -> w
                .like(User::getUsername, search)
                .or().like(User::getEmail, search)
                .or().like(User::getDisplayName, search));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(User::getStatus, status);
        }
        wrapper.orderByDesc(User::getCreatedAt);

        List<User> teachers = userMapper.selectList(wrapper);
        if (teachers.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> teacherIds = teachers.stream().map(User::getId).collect(Collectors.toList());
        List<Course> courses = courseMapper.selectList(
            new LambdaQueryWrapper<Course>()
                .in(Course::getTeacherId, teacherIds)
                .isNull(Course::getDeletedAt)
        );
        Map<String, List<Course>> courseMap = courses.stream()
            .collect(Collectors.groupingBy(Course::getTeacherId));

        return teachers.stream().map(t -> {
            List<Course> teacherCourses = courseMap.getOrDefault(t.getId(), new ArrayList<>());
            return AdminTeacherResponse.builder()
                .id(t.getId())
                .username(t.getUsername())
                .email(t.getEmail())
                .displayName(t.getDisplayName())
                .avatarUrl(t.getAvatarUrl())
                .phone(t.getPhone())
                .status(t.getStatus() != null ? t.getStatus() : "enabled")
                .courseCount(teacherCourses.size())
                .courseNames(teacherCourses.stream().map(Course::getName).collect(Collectors.toList()))
                .createdAt(t.getCreatedAt() != null ? t.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null)
                .build();
        }).collect(Collectors.toList());
    }

    @Override
    public AdminTeacherResponse createTeacher(String username, String email, String password, String displayName, String phone) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName);
        user.setPhone(phone);
        user.setRole("teacher");
        user.setStatus("enabled");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);

        return toResponse(user);
    }

    @Override
    public AdminTeacherResponse updateTeacher(String id, String displayName, String email, String phone) {
        User user = userMapper.selectById(id);
        if (user == null) throw new RuntimeException("用户不存在");
        if (!"teacher".equals(user.getRole())) throw new RuntimeException("只能管理教师账号");

        if (StringUtils.hasText(displayName)) user.setDisplayName(displayName);
        if (StringUtils.hasText(email)) user.setEmail(email);
        if (StringUtils.hasText(phone)) user.setPhone(phone);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);

        return toResponse(userMapper.selectById(id));
    }

    @Override
    public void updateTeacherStatus(String id, String status) {
        User user = userMapper.selectById(id);
        if (user == null) throw new RuntimeException("用户不存在");
        if (!"teacher".equals(user.getRole())) throw new RuntimeException("只能管理教师账号");
        if (!"enabled".equals(status) && !"disabled".equals(status)) {
            throw new RuntimeException("无效的状态值，仅支持 enabled / disabled");
        }
        user.setStatus(status);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
    }

    @Override
    public void resetPassword(String id) {
        User user = userMapper.selectById(id);
        if (user == null) throw new RuntimeException("用户不存在");
        if (!"teacher".equals(user.getRole())) throw new RuntimeException("只能重置教师密码");
        String defaultPassword = "123456";
        user.setPasswordHash(passwordEncoder.encode(defaultPassword));
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
    }

    @Override
    public void deleteTeacher(String id) {
        User user = userMapper.selectById(id);
        if (user == null) throw new RuntimeException("用户不存在");
        if (!"teacher".equals(user.getRole())) throw new RuntimeException("只能删除教师账号");
        userService.deleteById(id);
    }

    private AdminTeacherResponse toResponse(User user) {
        List<Course> courses = courseMapper.selectList(
            new LambdaQueryWrapper<Course>()
                .eq(Course::getTeacherId, user.getId())
                .isNull(Course::getDeletedAt)
        );
        return AdminTeacherResponse.builder()
            .id(user.getId())
            .username(user.getUsername())
            .email(user.getEmail())
            .displayName(user.getDisplayName())
            .avatarUrl(user.getAvatarUrl())
            .phone(user.getPhone())
            .status(user.getStatus() != null ? user.getStatus() : "enabled")
            .courseCount(courses.size())
            .courseNames(courses.stream().map(Course::getName).collect(Collectors.toList()))
            .createdAt(user.getCreatedAt() != null ? user.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null)
            .build();
    }
}
