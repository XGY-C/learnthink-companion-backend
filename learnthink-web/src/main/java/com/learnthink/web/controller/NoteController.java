package com.learnthink.web.controller;

import com.learnthink.common.dto.note.CreateNoteRequest;
import com.learnthink.common.dto.note.NoteStatsVO;
import com.learnthink.common.dto.note.NoteVO;
import com.learnthink.common.dto.note.UpdateNoteRequest;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.service.NoteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/notes")
@RequiredArgsConstructor
public class NoteController {

    private final NoteService noteService;

    @GetMapping
    public Result<List<NoteVO>> list(
            @RequestParam String courseId,
            @RequestParam(required = false) String notebookId,
            @RequestParam(required = false) String resourcePackId,
            @RequestParam(required = false) String resourceItemId) {
        String userId = UserContextUtil.getCurrentUserId();
        List<NoteVO> list = noteService.list(userId, courseId, notebookId, resourcePackId, resourceItemId);
        return Result.success(list);
    }

    @PostMapping
    public Result<NoteVO> create(@Valid @RequestBody CreateNoteRequest req) {
        String userId = UserContextUtil.getCurrentUserId();
        NoteVO note = noteService.create(userId, req);
        return Result.success(note);
    }

    @PutMapping("/{id}")
    public Result<NoteVO> update(@PathVariable String id, @Valid @RequestBody UpdateNoteRequest req) {
        String userId = UserContextUtil.getCurrentUserId();
        NoteVO note = noteService.update(id, userId, req);
        return Result.success(note);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        String userId = UserContextUtil.getCurrentUserId();
        noteService.softDelete(id, userId);
        return Result.success();
    }

    @GetMapping("/stats")
    public Result<NoteStatsVO> stats(@RequestParam String courseId) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(noteService.stats(userId, courseId));
    }
}
