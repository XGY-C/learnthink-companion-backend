package com.learnthink.web.controller.teacher;

import com.learnthink.common.dto.admin.KnowledgeGraphResult;
import com.learnthink.common.result.Result;
import com.learnthink.core.service.admin.KnowledgeGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/teacher/courses/{courseId}/knowledge-graph")
@RequiredArgsConstructor
public class TeacherKgController {

    private final KnowledgeGraphService knowledgeGraphService;

    @PostMapping("/generate")
    public Result<KnowledgeGraphResult> generateKnowledgeGraph(@PathVariable String courseId) {
        try {
            KnowledgeGraphResult result = knowledgeGraphService.generate(courseId);
            return Result.success(result);
        } catch (RuntimeException e) {
            return Result.error(400, e.getMessage());
        }
    }

    @GetMapping
    public Result<KnowledgeGraphResult> getKnowledgeGraph(@PathVariable String courseId) {
        try {
            KnowledgeGraphResult result = knowledgeGraphService.get(courseId);
            return Result.success(result);
        } catch (RuntimeException e) {
            return Result.error(400, e.getMessage());
        }
    }

    @PutMapping
    public Result<Void> saveKnowledgeGraph(@PathVariable String courseId,
                                            @RequestBody Map<String, Object> body) {
        try {
            knowledgeGraphService.save(courseId, body);
            return Result.success();
        } catch (RuntimeException e) {
            return Result.error(400, e.getMessage());
        }
    }
}
