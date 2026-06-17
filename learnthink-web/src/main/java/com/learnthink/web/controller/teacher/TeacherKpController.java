package com.learnthink.web.controller.teacher;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.common.dto.admin.KnowledgeGraphResult;
import com.learnthink.common.result.Result;
import com.learnthink.core.domain.entity.CourseKnowledgePoint;
import com.learnthink.core.repository.CourseKnowledgePointMapper;
import com.learnthink.core.service.admin.KnowledgeGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/teacher/courses/{courseId}/knowledge-points")
@RequiredArgsConstructor
public class TeacherKpController {

    private final CourseKnowledgePointMapper kpMapper;
    private final KnowledgeGraphService knowledgeGraphService;

    @GetMapping
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
        return Result.success(buildTree(byParent, "__root__"));
    }

    @PostMapping("/generate")
    public Result<KnowledgeGraphResult> generateKpTree(@PathVariable String courseId) {
        try {
            KnowledgeGraphResult result = knowledgeGraphService.generateKpTree(courseId);
            return Result.success(result);
        } catch (RuntimeException e) {
            return Result.error(400, e.getMessage());
        }
    }

    @PostMapping
    public Result<Void> createKp(@PathVariable String courseId, @RequestBody CourseKnowledgePoint kp) {
        kp.setId(null);
        kp.setCourseId(courseId);
        kp.setCreatedAt(LocalDateTime.now());
        kpMapper.insert(kp);
        return Result.success();
    }

    @PutMapping("/{kpId}")
    public Result<Void> updateKp(@PathVariable String courseId, @PathVariable String kpId,
                                  @RequestBody CourseKnowledgePoint kp) {
        kp.setId(kpId);
        kp.setUpdatedAt(LocalDateTime.now());
        kpMapper.updateById(kp);
        return Result.success();
    }

    @DeleteMapping("/{kpId}")
    public Result<Void> deleteKp(@PathVariable String courseId, @PathVariable String kpId) {
        kpMapper.deleteById(kpId);
        return Result.success();
    }

    private List<Map<String, Object>> buildTree(Map<String, List<CourseKnowledgePoint>> byParent, String parentKey) {
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
            node.put("learningObjectives", kp.getLearningObjectives());
            node.put("keywords", kp.getKeywords());
            node.put("prerequisiteKps", kp.getPrerequisiteKps());
            node.put("relatedKps", kp.getRelatedKps());
            node.put("children", buildTree(byParent, kp.getId()));
            result.add(node);
        }
        return result;
    }
}
