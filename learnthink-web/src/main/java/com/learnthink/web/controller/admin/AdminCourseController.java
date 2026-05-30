package com.learnthink.web.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.common.dto.admin.KnowledgeGraphResult;
import com.learnthink.common.result.Result;
import com.learnthink.core.domain.entity.Course;
import com.learnthink.core.domain.entity.CourseKnowledgePoint;
import com.learnthink.core.repository.CourseKnowledgePointMapper;
import com.learnthink.core.repository.CourseMapper;
import com.learnthink.core.service.admin.KnowledgeGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/admin/courses")
@RequiredArgsConstructor
public class AdminCourseController {

    private final CourseMapper courseMapper;
    private final KnowledgeGraphService knowledgeGraphService;
    private final CourseKnowledgePointMapper kpMapper;

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

    /**
     * 生成课程知识图谱（AntV G6 格式），基于主教材的内容简介+目录+RAG检索。
     */
    @PostMapping("/{courseId}/knowledge-graph/generate")
    public Result<KnowledgeGraphResult> generateKnowledgeGraph(@PathVariable String courseId) {
        try {
            KnowledgeGraphResult result = knowledgeGraphService.generate(courseId);
            return Result.success(result);
        } catch (RuntimeException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 获取已生成的知识图谱。
     */
    @GetMapping("/{courseId}/knowledge-graph")
    public Result<KnowledgeGraphResult> getKnowledgeGraph(@PathVariable String courseId) {
        try {
            KnowledgeGraphResult result = knowledgeGraphService.get(courseId);
            return Result.success(result);
        } catch (RuntimeException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 保存编辑后的知识图谱。
     */
    @PutMapping("/{courseId}/knowledge-graph")
    public Result<Void> saveKnowledgeGraph(@PathVariable String courseId,
                                            @RequestBody Map<String, Object> body) {
        try {
            knowledgeGraphService.save(courseId, body);
            return Result.success();
        } catch (RuntimeException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 获取知识点树（树结构，含 children 嵌套）。
     */
    @GetMapping("/{courseId}/knowledge-points")
    public Result<List<Map<String, Object>>> getKpTree(@PathVariable String courseId) {
        List<CourseKnowledgePoint> all = kpMapper.selectList(
            new LambdaQueryWrapper<CourseKnowledgePoint>()
                .eq(CourseKnowledgePoint::getCourseId, courseId)
                .orderByAsc(CourseKnowledgePoint::getSortOrder)
        );
        Map<String, List<CourseKnowledgePoint>> byParent = new LinkedHashMap<>();
        for (CourseKnowledgePoint kp : all) {
            String pid = kp.getParentId() != null ? kp.getParentId() : "__root__";
            byParent.computeIfAbsent(pid, k -> new ArrayList<>()).add(kp);
        }
        List<Map<String, Object>> tree = buildKpTree(byParent, "__root__");
        return Result.success(tree);
    }

    private List<Map<String, Object>> buildKpTree(Map<String, List<CourseKnowledgePoint>> byParent, String parentKey) {
        List<CourseKnowledgePoint> children = byParent.getOrDefault(parentKey, List.of());
        List<Map<String, Object>> result = new ArrayList<>();
        for (CourseKnowledgePoint kp : children) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", kp.getId());
            node.put("courseId", kp.getCourseId());
            node.put("parentId", kp.getParentId());
            node.put("name", kp.getName());
            node.put("kpType", kp.getKpType());
            node.put("scope", kp.getScope());
            node.put("depth", kp.getDepth());
            node.put("sortOrder", kp.getSortOrder());
            node.put("description", kp.getDescription());
            node.put("difficulty", kp.getDifficulty());
            node.put("estimatedMinutes", kp.getEstimatedMinutes());
            node.put("children", buildKpTree(byParent, kp.getId()));
            result.add(node);
        }
        return result;
    }

    /**
     * 生成知识点树（复用知识图谱的 RAG 检索流程，最后一步输出树结构）
     */
    @PostMapping("/{courseId}/knowledge-points/generate")
    public Result<KnowledgeGraphResult> generateKpTree(@PathVariable String courseId) {
        try {
            KnowledgeGraphResult result = knowledgeGraphService.generateKpTree(courseId);
            return Result.success(result);
        } catch (RuntimeException e) {
            return Result.error(400, e.getMessage());
        }
    }
}
