package com.learnthink.web.controller;

import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.domain.entity.Course;
import com.learnthink.core.service.CourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户端课程控制器
 * 提供课程浏览、选课、退课功能
 */
@RestController
@RequestMapping("/courses")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;

    /**
     * 获取我已加入的课程列表
     */
    @GetMapping("/my")
    public Result<List<Map<String, Object>>> getMyCourses() {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(courseService.getMyCourses(userId));
    }

    /**
     * 获取可选课程列表（当前用户未选的）
     */
    @GetMapping
    public Result<List<Course>> getAvailableCourses() {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(courseService.getAvailableCourses(userId));
    }

    /**
     * 获取课程详情
     */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> getCourseDetail(@PathVariable String id) {
        String userId = UserContextUtil.getCurrentUserId();
        Map<String, Object> detail = courseService.getCourseDetail(id, userId);
        if (detail == null) {
            return Result.error(404, "课程不存在");
        }
        return Result.success(detail);
    }

    /**
     * 选课
     */
    @PostMapping("/enroll")
    public Result<Map<String, Object>> enrollCourse(@RequestBody Map<String, String> body) {
        String courseId = body.get("courseId");
        if (courseId == null || courseId.trim().isEmpty()) {
            return Result.error(400, "courseId 不能为空");
        }

        String userId = UserContextUtil.getCurrentUserId();
        try {
            courseService.enrollCourse(userId, courseId.trim());
            Course course = courseService.getAvailableCourses(userId).stream()
                    .filter(c -> c.getId().equals(courseId))
                    .findFirst().orElse(null);
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("courseId", courseId);
            data.put("courseName", course != null ? course.getName() : "");
            data.put("courseEmoji", course != null ? course.getEmoji() : "📚");
            data.put("enrolledAt", java.time.LocalDateTime.now());
            return Result.success(data, "选课成功");
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        } catch (IllegalStateException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 退课
     */
    @PostMapping("/leave")
    public Result<Void> leaveCourse(@RequestBody Map<String, String> body) {
        String courseId = body.get("courseId");
        if (courseId == null || courseId.trim().isEmpty()) {
            return Result.error(400, "courseId 不能为空");
        }

        String userId = UserContextUtil.getCurrentUserId();
        try {
            courseService.leaveCourse(userId, courseId.trim());
            return Result.success(null, "已退课");
        } catch (IllegalStateException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 获取课程教材信息（书名、作者、简介、目录）
     */
    @GetMapping("/{id}/textbook")
    public Result<Map<String, Object>> getTextbookInfo(@PathVariable String id) {
        Map<String, Object> info = courseService.getTextbookInfo(id);
        if (info == null) {
            return Result.error(404, "该课程暂无教材信息");
        }
        return Result.success(info);
    }
}
