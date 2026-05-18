package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.core.domain.entity.Course;
import com.learnthink.core.domain.entity.Profile;
import com.learnthink.core.domain.entity.UserCourseEnrollment;
import com.learnthink.core.repository.CourseMapper;
import com.learnthink.core.repository.ProfileMapper;
import com.learnthink.core.repository.UserCourseEnrollmentMapper;
import com.learnthink.core.service.CourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CourseServiceImpl implements CourseService {

    private final CourseMapper courseMapper;
    private final UserCourseEnrollmentMapper enrollmentMapper;
    private final ProfileMapper profileMapper;

    @Override
    public List<Map<String, Object>> getMyCourses(String userId) {
        // 查询已选课程（JOIN courses 过滤已删除的）
        List<UserCourseEnrollment> enrollments = enrollmentMapper.selectList(
                new LambdaQueryWrapper<UserCourseEnrollment>()
                        .eq(UserCourseEnrollment::getUserId, userId)
                        .orderByDesc(UserCourseEnrollment::getEnrolledAt)
        );

        if (enrollments.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> courseIds = enrollments.stream()
                .map(UserCourseEnrollment::getCourseId)
                .collect(Collectors.toList());

        List<Course> courses = courseMapper.selectList(
                new LambdaQueryWrapper<Course>()
                        .in(Course::getId, courseIds)
                        .isNull(Course::getDeletedAt)
        );

        Map<String, Course> courseMap = courses.stream()
                .collect(Collectors.toMap(Course::getId, c -> c));

        List<Map<String, Object>> result = new ArrayList<>();
        for (UserCourseEnrollment enrollment : enrollments) {
            Course course = courseMap.get(enrollment.getCourseId());
            if (course == null) continue;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", course.getId());
            item.put("name", course.getName());
            item.put("emoji", course.getEmoji() != null ? course.getEmoji() : "📚");
            item.put("enrolledAt", enrollment.getEnrolledAt());

            // 轻量进度统计
            Map<String, Object> progress = new LinkedHashMap<>();
            progress.put("tasksCompleted", 0);
            progress.put("resourcesGenerated", 0);
            progress.put("pathProgressPercent", 0);
            item.put("progress", progress);

            result.add(item);
        }
        return result;
    }

    @Override
    public List<Course> getAvailableCourses(String userId) {
        // 获取用户已选课程 ID
        List<UserCourseEnrollment> enrollments = enrollmentMapper.selectList(
                new LambdaQueryWrapper<UserCourseEnrollment>()
                        .eq(UserCourseEnrollment::getUserId, userId)
        );
        Set<String> enrolledIds = enrollments.stream()
                .map(UserCourseEnrollment::getCourseId)
                .collect(Collectors.toSet());

        // 查询未选且未删除的课程
        LambdaQueryWrapper<Course> wrapper = new LambdaQueryWrapper<Course>()
                .isNull(Course::getDeletedAt)
                .orderByDesc(Course::getCreatedAt);

        if (!enrolledIds.isEmpty()) {
            wrapper.notIn(Course::getId, enrolledIds);
        }

        return courseMapper.selectList(wrapper);
    }

    @Override
    public Map<String, Object> getCourseDetail(String courseId, String userId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null || course.getDeletedAt() != null) {
            return null;
        }

        // 查询选课人数
        long enrolledCount = enrollmentMapper.selectCount(
                new LambdaQueryWrapper<UserCourseEnrollment>()
                        .eq(UserCourseEnrollment::getCourseId, courseId)
        );

        // 查询当前用户是否已选
        long userEnrolled = enrollmentMapper.selectCount(
                new LambdaQueryWrapper<UserCourseEnrollment>()
                        .eq(UserCourseEnrollment::getUserId, userId)
                        .eq(UserCourseEnrollment::getCourseId, courseId)
        );

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", course.getId());
        detail.put("name", course.getName());
        detail.put("description", course.getDescription());
        detail.put("emoji", course.getEmoji() != null ? course.getEmoji() : "📚");
        detail.put("enrolledCount", enrolledCount);
        detail.put("isEnrolled", userEnrolled > 0);
        detail.put("createdAt", course.getCreatedAt());

        return detail;
    }

    @Override
    @Transactional
    public void enrollCourse(String userId, String courseId) {
        // 校验课程存在且未删除
        Course course = courseMapper.selectById(courseId);
        if (course == null || course.getDeletedAt() != null) {
            throw new IllegalArgumentException("课程不存在");
        }

        // 校验是否已选
        Long count = enrollmentMapper.selectCount(
                new LambdaQueryWrapper<UserCourseEnrollment>()
                        .eq(UserCourseEnrollment::getUserId, userId)
                        .eq(UserCourseEnrollment::getCourseId, courseId)
        );
        if (count > 0) {
            throw new IllegalStateException("你已经加入该课程了");
        }

        // 创建选课记录
        UserCourseEnrollment enrollment = new UserCourseEnrollment();
        enrollment.setUserId(userId);
        enrollment.setCourseId(courseId);
        enrollmentMapper.insert(enrollment);

        // 初始化画像记录（如果尚未存在）
        Profile existingProfile = profileMapper.selectOne(
                new LambdaQueryWrapper<Profile>()
                        .eq(Profile::getUserId, userId)
                        .eq(Profile::getCourseId, courseId)
        );
        if (existingProfile == null) {
            Profile profile = new Profile();
            profile.setUserId(userId);
            profile.setCourseId(courseId);
            profile.setCurrentVersion(0);
            profile.setUpdatedAt(LocalDateTime.now());
            profileMapper.insert(profile);
        }
    }

    @Override
    @Transactional
    public void leaveCourse(String userId, String courseId) {
        // 校验是否已选
        UserCourseEnrollment enrollment = enrollmentMapper.selectOne(
                new LambdaQueryWrapper<UserCourseEnrollment>()
                        .eq(UserCourseEnrollment::getUserId, userId)
                        .eq(UserCourseEnrollment::getCourseId, courseId)
        );
        if (enrollment == null) {
            throw new IllegalStateException("你未加入该课程");
        }

        enrollmentMapper.deleteById(enrollment.getId());
    }
}
