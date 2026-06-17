package com.learnthink.web.controller.teacher;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.domain.entity.Course;
import com.learnthink.core.repository.CourseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/teacher/courses")
@RequiredArgsConstructor
public class TeacherCourseController {

    private final CourseMapper courseMapper;

    @GetMapping
    public Result<List<Course>> listMyCourses() {
        String userId = UserContextUtil.getCurrentUserId();
        List<Course> courses = courseMapper.selectList(
                new LambdaQueryWrapper<Course>()
                        .eq(Course::getTeacherId, userId)
                        .isNull(Course::getDeletedAt)
                        .orderByDesc(Course::getCreatedAt)
        );
        return Result.success(courses);
    }

    @GetMapping("/{id}")
    public Result<Course> getCourse(@PathVariable String id) {
        Course course = courseMapper.selectOne(
                new LambdaQueryWrapper<Course>()
                        .eq(Course::getId, id)
                        .isNull(Course::getDeletedAt)
        );
        if (course == null) return Result.error(404, "课程不存在");
        return Result.success(course);
    }
}
