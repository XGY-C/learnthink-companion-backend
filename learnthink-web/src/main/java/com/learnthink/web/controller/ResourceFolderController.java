package com.learnthink.web.controller;

import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.service.ResourceFolderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/resources/folders")
@RequiredArgsConstructor
public class ResourceFolderController {

    private final ResourceFolderService folderService;

    @GetMapping
    public Result<List<Map<String, Object>>> getTree(@RequestParam String courseId) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(folderService.getTree(userId, courseId));
    }

    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Map<String, String> req) {
        String userId = UserContextUtil.getCurrentUserId();
        String courseId = req.get("courseId");
        String parentId = req.get("parentId");
        String name = req.get("name");
        return Result.success(folderService.create(userId, courseId, parentId, name));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody Map<String, Object> req) {
        String userId = UserContextUtil.getCurrentUserId();
        String name = (String) req.get("name");
        String parentId = (String) req.get("parentId");
        Integer sortOrder = req.get("sortOrder") != null ? ((Number) req.get("sortOrder")).intValue() : null;
        folderService.update(id, userId, name, parentId, sortOrder);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        String userId = UserContextUtil.getCurrentUserId();
        folderService.softDelete(id, userId);
        return Result.success();
    }
}
