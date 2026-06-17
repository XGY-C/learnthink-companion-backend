package com.learnthink.web.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.common.result.Result;
import com.learnthink.core.domain.entity.Course;
import com.learnthink.core.repository.CourseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

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

    @PostMapping
    public Result<Course> createCourse(@RequestBody Course course) {
        course.setId(null);
        course.setCreatedAt(LocalDateTime.now());
        if (course.getEnabled() == null) course.setEnabled(true);
        courseMapper.insert(course);
        return Result.success(course, "课程已创建");
    }

    @PutMapping("/{id}")
    public Result<Course> updateCourse(@PathVariable String id, @RequestBody Course course) {
        Course existing = courseMapper.selectOne(
                new LambdaQueryWrapper<Course>()
                        .eq(Course::getId, id)
                        .isNull(Course::getDeletedAt)
        );
        if (existing == null) return Result.error(404, "课程不存在");
        course.setId(id);
        course.setUpdatedAt(LocalDateTime.now());
        courseMapper.updateById(course);
        return Result.success(courseMapper.selectById(id), "课程已更新");
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteCourse(@PathVariable String id) {
        Course existing = courseMapper.selectOne(
                new LambdaQueryWrapper<Course>()
                        .eq(Course::getId, id)
                        .isNull(Course::getDeletedAt)
        );
        if (existing == null) return Result.error(404, "课程不存在");
        existing.setDeletedAt(LocalDateTime.now());
        courseMapper.updateById(existing);
        return Result.success(null, "课程已删除");
    }
}
