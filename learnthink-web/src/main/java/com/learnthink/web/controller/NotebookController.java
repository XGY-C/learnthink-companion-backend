package com.learnthink.web.controller;

import com.learnthink.common.dto.note.CreateNotebookRequest;
import com.learnthink.common.dto.note.NotebookVO;
import com.learnthink.common.dto.note.UpdateNotebookRequest;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.service.NotebookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/notebooks")
@RequiredArgsConstructor
public class NotebookController {

    private final NotebookService notebookService;

    @GetMapping
    public Result<List<NotebookVO>> list(@RequestParam String courseId) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(notebookService.list(userId, courseId));
    }

    @PostMapping
    public Result<NotebookVO> create(@Valid @RequestBody CreateNotebookRequest req) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(notebookService.create(userId, req));
    }

    @PutMapping("/{id}")
    public Result<NotebookVO> update(@PathVariable String id, @RequestBody UpdateNotebookRequest req) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(notebookService.update(userId, id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        String userId = UserContextUtil.getCurrentUserId();
        notebookService.softDelete(userId, id);
        return Result.success();
    }

    @GetMapping("/default")
    public Result<NotebookVO> getDefault(@RequestParam String courseId) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(notebookService.getDefault(userId, courseId));
    }
}
