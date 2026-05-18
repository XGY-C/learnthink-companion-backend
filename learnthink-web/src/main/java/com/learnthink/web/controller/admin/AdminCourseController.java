package com.learnthink.web.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.common.result.Result;
import com.learnthink.core.domain.entity.Course;
import com.learnthink.core.repository.CourseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理端 — 课程基础信息（仅用于下拉列表等轻量查询）.
 */
@RestController
@RequestMapping("/admin/courses")
@RequiredArgsConstructor
public class AdminCourseController {

    private final CourseMapper courseMapper;

    @GetMapping
    public Result<List<Course>> listCourses() {
        List<Course> courses = courseMapper.selectList(
                new LambdaQueryWrapper<Course>()
                        .isNull(Course::getDeletedAt)
                        .orderByDesc(Course::getCreatedAt)
        );
        return Result.success(courses);
    }
}
